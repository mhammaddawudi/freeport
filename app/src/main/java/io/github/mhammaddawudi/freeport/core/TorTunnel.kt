package io.github.mhammaddawudi.freeport.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tor, reached through Snowflake: the last resort when nothing else gets out.
 *
 * Snowflake hides the way into Tor behind short-lived WebRTC connections to
 * volunteers' browsers, found through a broker reached by domain fronting, so
 * there is no bridge address for a censor to list. It is slow and its round
 * trips are long, but it is free, has no cap, and runs on nobody's servers
 * that can be blocked in one go.
 *
 * Tor is Arti, the Tor Project's Rust client, and Snowflake is its Go
 * client; both are cross-compiled and shipped as `libarti.so` and
 * `libsnowflake.so` so Android extracts them executable. Arti runs as a child
 * process and starts Snowflake itself as a pluggable transport.
 */
class TorTunnel(context: Context, private val log: CoreLog = CoreLog) : DirectTunnel {

    private val libraries = File(context.applicationInfo.nativeLibraryDir)
    private val arti = File(libraries, "libarti.so")
    private val snowflake = File(libraries, "libsnowflake.so")
    private val dataDir = File(context.filesDir, "tor")
    private val snowflakeLog = File(dataDir, "snowflake.log")

    @Volatile private var process: Process? = null
    @Volatile private var bootstrapped = false

    override val name = "Tor"
    override val isAvailable: Boolean get() = arti.isFile && snowflake.isFile

    override val health: PsiphonRelay.Health
        get() = when {
            process?.isAlive != true -> PsiphonRelay.Health.Stopped
            bootstrapped -> PsiphonRelay.Health.Up
            else -> PsiphonRelay.Health.Searching
        }

    override suspend fun open(socksPort: Int, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        close()
        if (!isAvailable) return@withContext "tor is not in this build"
        dataDir.mkdirs()
        val configFile = File(dataDir, "arti.toml")
        reapStale(configFile)
        configFile.writeText(config(socksPort))
        snowflakeLog.delete()

        val ready = CompletableDeferred<String?>()
        val started = runCatching {
            ProcessBuilder(arti.absolutePath, "proxy", "-c", configFile.absolutePath)
                .directory(dataDir)
                .redirectErrorStream(true)
                .apply { environment()["HOME"] = dataDir.absolutePath }
                .start()
        }.getOrElse { return@withContext "tor did not start: ${it.message}" }
        process = started
        bootstrapped = false
        pidOf(started)?.let { pid -> runCatching { File(dataDir, PID_FILE).writeText(pid.toString()) } }
        log.info("[*] tor: starting through snowflake")
        Thread({ readLog(started, ready) }, "arti-log").apply { isDaemon = true }.start()

        val outcome = withTimeoutOrNull(timeoutMs) { ready.await() }
            ?: "tor did not finish connecting within ${timeoutMs / 1000}s"
        if (outcome.isNotEmpty()) {
            close()
            reportSnowflake()
        }
        outcome.takeIf { it.isNotEmpty() }
    }

    override fun close() {
        val running = process ?: return
        process = null
        bootstrapped = false
        running.destroy()
        if (!runCatching { running.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) running.destroyForcibly()
        File(dataDir, PID_FILE).delete()
    }

    /** Snowflake's own last words: whether the broker, the volunteers or the bridge was out of reach. */
    private fun reportSnowflake() {
        val lines = runCatching { snowflakeLog.readLines() }.getOrNull().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            log.warn("[-] snowflake wrote nothing; it may not have started")
            return
        }
        lines.takeLast(SNOWFLAKE_LINES).forEach { log.info("[*] snowflake: ${it.take(200)}") }
    }

    private fun readLog(running: Process, ready: CompletableDeferred<String?>) {
        runCatching {
            running.inputStream.bufferedReader().forEachLine { raw ->
                val line = ANSI.replace(raw, "").trim()
                if (line.isEmpty()) return@forEachLine
                when {
                    BOOTSTRAPPED.containsMatchIn(line) -> {
                        bootstrapped = true
                        if (ready.complete("")) log.good("[+] tor: connected through snowflake")
                    }
                    PROGRESS.containsMatchIn(line) -> log.info("[*] tor: ${line.substringAfter("INFO").trim().take(160)}")
                    line.contains(" WARN ") || line.contains(" ERROR ") ->
                        log.warn("[-] tor: ${line.substringAfter(" WARN ").substringAfter(" ERROR ").trim().take(200)}")
                }
            }
        }
        ready.complete("tor exited")
    }

    private fun config(socksPort: Int): String = buildString {
        appendLine("[proxy]")
        appendLine("socks_listen = \"127.0.0.1:$socksPort\"")
        appendLine()
        appendLine("[storage]")
        appendLine("cache_dir = ${quote(File(dataDir, "cache").absolutePath)}")
        appendLine("state_dir = ${quote(File(dataDir, "state").absolutePath)}")
        appendLine()
        // App folders on Android are not laid out the way Arti's permission
        // check expects, and nothing else can read them anyway.
        appendLine("[storage.permissions]")
        appendLine("dangerously_trust_everyone = true")
        appendLine()
        appendLine("[logging]")
        appendLine("console = \"info\"")
        appendLine()
        appendLine("[bridges]")
        appendLine("enabled = true")
        appendLine("bridges = [")
        SNOWFLAKE_BRIDGES.forEach { appendLine("  ${quote(it)},") }
        appendLine("]")
        appendLine()
        appendLine("[[bridges.transports]]")
        appendLine("protocols = [\"snowflake\"]")
        appendLine("path = ${quote(snowflake.absolutePath)}")
        appendLine("arguments = [\"-log\", ${quote(snowflakeLog.absolutePath)}]")
        appendLine("run_on_startup = false")
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Stops an Arti an earlier run of the app left behind; it would hold the state folder's lock. */
    private fun reapStale(configFile: File) {
        val pidFile = File(dataDir, PID_FILE)
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return
        pidFile.delete()
        val command = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrNull() ?: return
        if (!command.contains("libarti.so") || !command.contains(configFile.absolutePath)) return
        log.info("[*] stopping a tor left over from an earlier run (pid $pid)")
        android.os.Process.killProcess(pid)
    }

    private fun pidOf(running: Process): Int? = runCatching {
        running.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(running)
    }.getOrNull()

    internal companion object {
        const val PID_FILE = "arti.pid"
        const val SNOWFLAKE_LINES = 6
        private val ANSI = Regex("""\u001B\[[0-9;]*m""")
        private val BOOTSTRAPPED = Regex("""Sufficiently bootstrapped|SOCKS now functional""", RegexOption.IGNORE_CASE)
        private val PROGRESS = Regex("""bootstrap""", RegexOption.IGNORE_CASE)

        /** Snowflake's own bridge lines, as its client ships them (v2.15). */
        val SNOWFLAKE_BRIDGES = listOf(
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ " +
                "fronts=www.cdn77.com,www.phpmyadmin.net $ICE utls-imitate=hellorandomizedalpn",
            "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
                "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ " +
                "fronts=www.cdn77.com,www.phpmyadmin.net $ICE utls-imitate=hellorandomizedalpn",
        )

        private const val ICE =
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
                "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
                "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
    }
}
