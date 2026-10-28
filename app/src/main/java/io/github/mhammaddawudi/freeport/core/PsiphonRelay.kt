package io.github.mhammaddawudi.freeport.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A second exit, outside Cloudflare, for services that refuse Iran.
 *
 * Cloudflare gives every WARP session an egress address that geolocates to
 * the country the device connects from, however many WARP hops are nested:
 * from an Iranian line, MASQUE, WireGuard and warp in warp all come out as
 * `loc=IR`, and ChatGPT, Supercell games and licensed music on Instagram
 * refuse them. Psiphon, dialled *through* the WARP tunnel, leaves from one
 * of its own servers abroad instead, so the sites see that country.
 *
 * The client is Psiphon's own console client, cross-compiled and shipped as
 * `libpsiphon.so` so Android extracts it executable into the library folder.
 * It runs as a child process; its notices arrive as JSON lines on stderr.
 */
class PsiphonRelay(context: Context, private val log: CoreLog = CoreLog) : UnlockRelay {

    private val binary = File(context.applicationInfo.nativeLibraryDir, "libpsiphon.so")
    private val dataDir = File(context.filesDir, "psiphon")

    @Volatile private var process: Process? = null
    @Volatile private var tunnel: Tunnel = Tunnel.Waiting
    @Volatile private var starting = false

    /** The country the running client was asked for; empty for its own pick. */
    @Volatile override var country: String = ""
        private set

    val isAvailable: Boolean get() = binary.isFile

    /** True while the client process is running. */
    val isAlive: Boolean get() = process?.isAlive == true

    /** True while [start] runs, so a health check does not start a second client over it. */
    override val isStarting: Boolean get() = starting

    /** What the running client says about its tunnel. */
    override val health: Health
        get() = when {
            !isAlive -> Health.Stopped
            tunnel == Tunnel.Up -> Health.Up
            else -> Health.Searching
        }

    enum class Health { Up, Searching, Stopped }

    /** The client's own view of its tunnel, from its `Tunnels` notices. */
    internal enum class Tunnel { Waiting, Up, Lost }

    /**
     * Starts the client, dialling out through [upstreamSocksPort] and serving
     * SOCKS5 on [localPort]. Returns null once a tunnel is up, or why not.
     */
    suspend fun start(
        upstreamSocksPort: Int,
        localPort: Int,
        country: String,
        timeoutMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        starting = true
        try {
            launch(upstreamSocksPort, localPort, country, timeoutMs)
        } finally {
            starting = false
        }
    }

    private suspend fun launch(upstreamSocksPort: Int, localPort: Int, country: String, timeoutMs: Long): String? {
        stop()
        if (!isAvailable) return "the psiphon client is not in this build"
        dataDir.mkdirs()
        val configFile = File(dataDir, "config.json")
        reapStale(configFile)
        configFile.writeText(config(upstreamSocksPort, localPort, country).toString())

        val ready = CompletableDeferred<String?>()
        val started = runCatching {
            ProcessBuilder(binary.absolutePath, "-config", configFile.absolutePath)
                .directory(dataDir)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return "psiphon did not start: ${it.message}" }
        process = started
        tunnel = Tunnel.Waiting
        this.country = country
        pidOf(started)?.let { pid -> runCatching { File(dataDir, PID_FILE).writeText(pid.toString()) } }
        log.info("[*] region unlock: starting psiphon${if (country.isEmpty()) "" else " towards $country"}")

        // Drained on its own thread for the life of the process: a full pipe
        // would stall the client.
        Thread({ readNotices(started, ready) }, "psiphon-notices").apply { isDaemon = true }.start()

        val outcome = withTimeoutOrNull(timeoutMs) { ready.await() }
            ?: "psiphon found no server within ${timeoutMs / 1000}s"
        if (outcome.isNotEmpty()) stop()
        return outcome.takeIf { it.isNotEmpty() }
    }

    fun stop() {
        val running = process ?: return
        process = null
        running.destroy()
        if (!runCatching { running.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)) {
            running.destroyForcibly()
        }
        File(dataDir, PID_FILE).delete()
    }

    /**
     * Stops a client an earlier run of the app left behind (the app process
     * was killed before it could stop it). One holding the data folder makes
     * every new one exit at once. The pid is only signalled while it is still
     * a psiphon with this config, never a process that has since reused it.
     */
    private fun reapStale(configFile: File) {
        val pidFile = File(dataDir, PID_FILE)
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return
        pidFile.delete()
        if (!isOurClient(pid, configFile)) return
        log.info("[*] stopping a psiphon left over from an earlier run (pid $pid)")
        android.os.Process.killProcess(pid)
        repeat(20) {
            if (!isOurClient(pid, configFile)) return
            Thread.sleep(100)
        }
        android.os.Process.sendSignal(pid, SIGKILL)
    }

    /**
     * The SDK offers no `Process.pid()`; Android's process class keeps the
     * pid in a private field of that name. Null if that ever changes, which
     * only loses the clean-up of a client left behind.
     */
    private fun pidOf(running: Process): Int? = runCatching {
        running.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(running)
    }.getOrNull()

    private fun isOurClient(pid: Int, configFile: File): Boolean {
        val command = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrNull() ?: return false
        return command.contains("libpsiphon.so") && command.contains(configFile.absolutePath)
    }

    private fun readNotices(running: Process, ready: CompletableDeferred<String?>) {
        runCatching {
            running.inputStream.bufferedReader().forEachLine { line ->
                val notice = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val data = notice.optJSONObject("data") ?: JSONObject()
                when (notice.optString("noticeType")) {
                    "Tunnels" -> {
                        val was = tunnel
                        tunnel = nextTunnel(was, data.optInt("count"))
                        when {
                            was == Tunnel.Waiting && tunnel == Tunnel.Up ->
                                if (ready.complete("")) log.good("[+] region unlock: psiphon tunnel is up")
                            was == Tunnel.Lost && tunnel == Tunnel.Up ->
                                log.good("[+] region unlock: psiphon found another tunnel")
                            was == Tunnel.Up && tunnel == Tunnel.Lost ->
                                log.warn("[-] region unlock: psiphon lost its tunnel, it is looking for another")
                        }
                    }
                    "ActiveTunnel" -> log.info(
                        "[*] psiphon via ${data.optString("protocol")} ${data.optString("region")}".trimEnd(),
                    )
                    "Error" -> log.warn("[-] psiphon: ${data.optString("message").take(240)}")
                }
            }
        }
        // The process ended, either stopped or crashed.
        ready.complete("psiphon exited")
    }

    private fun config(upstreamPort: Int, localPort: Int, country: String) = JSONObject()
        .put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        .put("SponsorId", "FFFFFFFFFFFFFFFF")
        .put("ClientPlatform", "Android_freeport")
        .put("ClientVersion", "1")
        .put("RemoteServerListURLs", JSONArray().put(JSONObject().put("URL", SERVER_LIST_URL_B64)))
        .put("RemoteServerListSignaturePublicKey", SERVER_LIST_KEY)
        // Through a SOCKS proxy only TCP protocols can be dialled.
        .put("LimitTunnelProtocols", JSONArray(TCP_PROTOCOLS))
        .put("InproxyTunnelProtocolSelectionProbability", 0.0)
        .put("DataRootDirectory", dataDir.absolutePath)
        .put("LocalSocksProxyPort", localPort)
        .put("DisableLocalHTTPProxy", true)
        .put("UpstreamProxyURL", "socks5://127.0.0.1:$upstreamPort")
        .put("EmitBytesTransferred", false)
        .apply { if (country.isNotEmpty()) put("EgressRegion", country) }

    internal companion object {
        const val PID_FILE = "psiphon.pid"
        const val SIGKILL = 9

        /** The tunnel state after a `Tunnels` notice with [count] tunnels. */
        fun nextTunnel(current: Tunnel, count: Int): Tunnel = when {
            count > 0 -> Tunnel.Up
            current == Tunnel.Waiting -> Tunnel.Waiting
            else -> Tunnel.Lost
        }

        /** base64 of Psiphon's public remote server list address. */
        const val SERVER_LIST_URL_B64 =
            "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tLy9wc2lwaG9uL3dlYi9tanI0LXAyM3ItcHV3bC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk"

        /** Psiphon's public key for that list; the client refuses an unsigned one. */
        const val SERVER_LIST_KEY =
            "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
                "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
                "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
                "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
                "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
                "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
                "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
                "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
                "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
                "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

        val TCP_PROTOCOLS = listOf(
            "SSH", "OSSH", "TLS-OSSH",
            "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH", "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
            "SHADOWSOCKS-OSSH", "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH",
        )
    }
}
