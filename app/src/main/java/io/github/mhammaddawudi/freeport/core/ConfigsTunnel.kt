package io.github.mhammaddawudi.freeport.core

import android.content.Context
import io.github.mhammaddawudi.freeport.core.configs.ConfigFeed
import io.github.mhammaddawudi.freeport.core.configs.ConfigPool
import io.github.mhammaddawudi.freeport.core.configs.ConfigTester
import io.github.mhammaddawudi.freeport.core.configs.ShareLink
import io.github.mhammaddawudi.freeport.core.configs.Xray
import io.github.mhammaddawudi.freeport.core.configs.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The public V2Ray configs shared on GitHub, as one protocol: the pool of
 * those that work on this network ([ConfigPool]), and the fastest of them
 * carried by Xray on the session's SOCKS port. A config that stops working
 * is set aside and the next one takes over on the reconnect.
 *
 * These servers belong to strangers: what crosses them unencrypted can be
 * read there. HTTPS, which is nearly everything, stays private.
 */
class ConfigsTunnel(
    private val context: Context,
    /** The edge board, where every phone's tests meet; empty for none. */
    board: String = "",
    private val log: CoreLog = CoreLog,
) : DirectTunnel {

    private val folder = File(context.filesDir, "configs")
    private val xray = Xray(File(context.applicationInfo.nativeLibraryDir, "libxray.so"), folder)
    private val tester = ConfigTester(xray, log)
    private val feed = ConfigFeed(folder, log, board = board)
    private val pool = ConfigPool(folder, feed, tester, log)

    /** Fetches the lists when GitHub is filtered and nothing else is up: Psiphon, briefly. */
    private val bootstrap = PsiphonDirect(context, log)

    @Volatile private var running: Xray.Running? = null
    @Volatile private var current: ShareLink? = null
    @Volatile private var openedAt = 0L
    @Volatile private var deepAt = 0L

    override val name = "V2Ray configs"
    override val isAvailable: Boolean get() = xray.isAvailable

    override val health: PsiphonRelay.Health
        get() = when {
            running?.isAlive == true -> PsiphonRelay.Health.Up
            else -> PsiphonRelay.Health.Stopped
        }

    override suspend fun open(socksPort: Int, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        close()
        if (!isAvailable) return@withContext "xray is not in this build"
        val network = NetworkKey.current(context)
        val started = System.currentTimeMillis()
        // A config that dropped soon after it was chosen is not tried first again.
        current?.takeIf { started - openedAt < QUICK_DROP_MS }?.let { pool.markFailed(network, it) }

        if (pool.isStale(network)) {
            log.info("[*] configs: building the pool for this network")
            val helpers = mutableListOf<AutoCloseable>()
            try {
                pool.refresh(network, timeoutMs - TRY_RESERVE_MS, via = { routesToGitHub(network, helpers) })
            } finally {
                helpers.forEach { runCatching { it.close() } }
            }
        }
        for (link in pool.candidates(network).take(MAX_TRIES)) {
            if (System.currentTimeMillis() - started > timeoutMs) break
            if (tryLink(link, socksPort)) {
                current = link
                openedAt = System.currentTimeMillis()
                return@withContext null
            }
            pool.markFailed(network, link)
        }
        "no working config on this network"
    }

    /**
     * Local SOCKS ports that may reach GitHub when the bare line does not: a
     * config that worked here before, else Psiphon when no copy is kept.
     */
    private suspend fun routesToGitHub(network: String, helpers: MutableList<AutoCloseable>): List<Int> {
        val known = pool.candidates(network).firstOrNull()
        if (known != null) {
            val port = ConfigTester.freePorts(1).first()
            xray.start(listOf(Xray.Route(known, port)), "fetch").getOrNull()?.let {
                helpers += it
                log.info("[*] configs: fetching the lists through ${known.label}")
                return listOf(port)
            }
        }
        if (feed.hasCopy || !bootstrap.isAvailable) return emptyList()
        val port = ConfigTester.freePorts(1).first()
        log.info("[*] configs: fetching the lists through Psiphon")
        helpers += AutoCloseable { bootstrap.close() }
        return if (bootstrap.open(port, BOOTSTRAP_MS) == null) listOf(port) else emptyList()
    }

    /**
     * While another session is up: refreshes the lists through it and, when
     * this network's pool is old, rebuilds the pool in the background, so the
     * fastest configs are ready the moment V2Ray is chosen. The tests run
     * over the bare line, the way the configs will be used.
     */
    override suspend fun warm(socksPort: Int) {
        if (!isAvailable || running != null) return
        feed.refreshThrough(socksPort)
        pool.share(socksPort)
        val network = NetworkKey.current(context)
        // A deep sieve downloads a few megabytes per config: at most every few hours.
        val due = System.currentTimeMillis() - deepAt > DEEP_EVERY_MS
        if (pool.isStale(network) || (due && pool.isShallow())) {
            deepAt = System.currentTimeMillis()
            log.info("[*] configs: a deep sieve of the public configs in the background")
            pool.refresh(network, BACKGROUND_BUDGET_MS, deep = true)
        }
    }

    override val isReady: Boolean get() = !pool.isStale(NetworkKey.current(context))

    override fun lastRttMs(): Long? = pool.bestDelay(NetworkKey.current(context))

    private suspend fun tryLink(link: ShareLink, socksPort: Int): Boolean {
        val started = xray.start(listOf(Xray.Route(link, socksPort)), "session", udp = true).getOrElse {
            log.warn("[-] configs: xray refused ${link.label}: ${it.message?.take(120)}")
            return false
        }
        val delay = tester.answers(socksPort)
        if (delay == null) {
            log.warn("[-] configs: ${link.label} did not answer")
            started.close()
            return false
        }
        running = started
        log.good("[+] configs: carrying through ${link.label}, ${delay}ms")
        pool.markGood(link, delay)
        // The board is often filtered on the bare line; this session reaches it.
        Thread({ kotlinx.coroutines.runBlocking { pool.share(socksPort) } }, "configs-share").apply { isDaemon = true }.start()
        return true
    }

    override fun close() {
        running?.close()
        running = null
    }

    private companion object {
        const val MAX_TRIES = 6
        const val TRY_RESERVE_MS = 20_000L
        const val QUICK_DROP_MS = 5 * 60_000L
        const val BOOTSTRAP_MS = 60_000L
        const val BACKGROUND_BUDGET_MS = 300_000L
        const val DEEP_EVERY_MS = 6 * 3_600_000L
    }
}
