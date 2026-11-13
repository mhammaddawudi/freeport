package io.github.mhammaddawudi.freeport.core.configs

import io.github.mhammaddawudi.freeport.core.CoreLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The configs that work, fastest first: one pool for every network, since a
 * server that carries on one line usually carries on the next, and a larger
 * pool has more to fall back on. Only failures are kept per network: a config
 * a network blocks is set aside there and still offered everywhere else.
 *
 * Every test is also sent to the shared pool on the edge board, so each
 * phone's work helps the others; what it sends waits until the board is
 * reachable, through the bare line or a tunnel.
 */
class ConfigPool(
    private val folder: File,
    private val feed: ConfigFeed,
    private val tester: ConfigTester,
    private val log: CoreLog = CoreLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val link: ShareLink, val delayMs: Long, val kbps: Long, val at: Long)

    private val file = File(folder, "pool.json")
    private var entries: List<Entry> = emptyList()

    /** "network|config key" → when it failed there. */
    private val failures = mutableMapOf<String, Long>()
    private val pending = mutableListOf<ConfigFeed.Report>()

    init {
        load()
    }

    /** Configs to try on [network], best first. */
    @Synchronized
    fun candidates(network: String): List<ShareLink> {
        val now = clock()
        return entries
            .filter { now - it.at < TTL_MS && now - (failures["$network|${it.link.key}"] ?: 0) > FAILED_FOR_MS }
            .sortedWith(compareByDescending<Entry> { it.kbps }.thenBy { it.delayMs })
            .map { it.link }
    }

    /** The quickest pooled config's round trip on [network]; null when none is pooled. */
    @Synchronized
    fun bestDelay(network: String): Long? {
        val usable = candidates(network).map { it.key }.toSet()
        return entries.filter { it.link.key in usable && it.delayMs > 0 }.minOfOrNull { it.delayMs }
    }

    /** Whether too few pooled configs have had their download measured: worth a deep sieve. */
    @Synchronized
    fun isShallow(): Boolean = entries.count { it.kbps >= FAST_KBPS } < DEEP_WANT.speedTested / 2

    /** Whether the pool is thin on [network], or old, and worth rebuilding. */
    fun isStale(network: String): Boolean {
        val newest = synchronized(this) { entries.maxOfOrNull { it.at } } ?: 0
        return candidates(network).size < MIN_HEALTHY || clock() - newest > REFRESH_MS
    }

    @Synchronized
    fun markFailed(network: String, link: ShareLink) {
        failures["$network|${link.key}"] = clock()
        pending += ConfigFeed.Report(link, ok = false)
        save()
    }

    /** A config carried a session: told to the shared pool. */
    @Synchronized
    fun markGood(link: ShareLink, delayMs: Long) {
        pending += ConfigFeed.Report(link, ok = true, delayMs = delayMs)
    }

    /** Sends what waits to the shared pool, over the bare line or through [socksPort]. */
    suspend fun share(socksPort: Int? = null) {
        val batch = synchronized(this) { pending.toList() }
        if (batch.isEmpty()) return
        if (feed.share(batch, socksPort)) {
            synchronized(this) { pending.removeAll(batch.toSet()) }
            log.info("[*] configs: ${batch.size} results shared with the pool")
        }
    }

    /**
     * Sieves the lists for configs that work here: TCP knock, a real request
     * through each, then the download of the quickest few. Pooled ones are
     * tested again first, then the shared pool's, then the rest.
     */
    suspend fun refresh(
        network: String,
        budgetMs: Long,
        via: suspend () -> List<Int> = { emptyList() },
        /** In the background: more servers, more configs, longer downloads. */
        deep: Boolean = false,
    ): Int =
        withContext(Dispatchers.Default) {
            val begun = clock()
            val known = synchronized(this@ConfigPool) { entries.map { it.link } }
            val fresh = feed.load(via)
            if (fresh.isEmpty() && known.isEmpty()) return@withContext 0
            val keys = known.map { it.key }.toSet()
            val pile = known + fresh.filter { it.key !in keys }.take(KNOCK_MAX)
            // Fetching may have taken a while; testing gets what is left, and at least its share.
            val deadline = maxOf(begun + budgetMs, clock() + MIN_TEST_MS)
            log.info("[*] configs: knocking on ${pile.size} servers")
            val want = if (deep) DEEP_WANT else Depth.QUICK
            val open = tester.knock(pile, want.knock, KNOCK_BUDGET_MS)
            log.info("[*] configs: ${open.size} servers answered; testing them through xray")

            val healthy = mutableListOf<ConfigTester.Healthy>()
            val tried = mutableListOf<ShareLink>()
            for (batch in open.chunked(BATCH)) {
                if (healthy.size >= want.healthy || clock() > deadline - want.speedReserveMs) break
                healthy += tester.probe(batch)
                tried += batch
                log.info("[*] configs: ${healthy.size} working so far")
            }
            val working = healthy.map { it.link.key }.toSet()
            synchronized(this@ConfigPool) {
                tried.filter { it.key !in working }.take(MAX_FAILED_REPORTS).forEach {
                    failures["$network|${it.key}"] = clock()
                    pending += ConfigFeed.Report(it, ok = false)
                }
            }
            if (healthy.isEmpty()) {
                log.warn("[-] configs: none of the public configs carries traffic on this network")
                share()
                return@withContext 0
            }
            val quickest = healthy.sortedBy { it.delayMs }
            // The download decides: a quick answer often hides a line too thin for video.
            val measured = (tester.measure(quickest.take(want.speedTested), want.windowMs) + quickest.drop(want.speedTested))
                .sortedWith(compareByDescending<ConfigTester.Healthy> { it.kbps }.thenBy { it.delayMs })
            val now = clock()
            synchronized(this@ConfigPool) {
                // Others still pooled and not retested stay, behind what was just measured.
                val kept = entries.filter { it.link.key !in working && now - it.at < TTL_MS }
                entries = (measured.map { Entry(it.link, it.delayMs, it.kbps, now) } + kept).take(MAX_POOLED)
                measured.forEach { pending += ConfigFeed.Report(it.link, ok = true, delayMs = it.delayMs, kbps = it.kbps) }
                save()
            }
            share()
            log.good("[+] configs: ${measured.size} working configs pooled, best ${measured.first().kbps} KB/s")
            measured.size
        }

    private fun load() = runCatching {
        val json = JSONObject(file.readText())
        val list = json.optJSONArray("entries") ?: JSONArray()
        entries = (0 until list.length()).mapNotNull { i ->
            val item = list.getJSONObject(i)
            ShareLink.parse(item.getString("link"))?.let { Entry(it, item.getLong("delay"), item.getLong("kbps"), item.getLong("at")) }
        }
        json.optJSONObject("failures")?.let { f -> f.keys().forEach { key -> failures[key] = f.getLong(key) } }
    }

    private fun save() {
        val now = clock()
        failures.entries.removeAll { now - it.value > FAILED_FOR_MS }
        val json = JSONObject()
            .put("entries", JSONArray(entries.map { JSONObject().put("link", it.link.raw).put("delay", it.delayMs).put("kbps", it.kbps).put("at", it.at) }))
            .put("failures", JSONObject(failures.toMap()))
        runCatching {
            folder.mkdirs()
            file.writeText(json.toString())
        }
    }

    /** How far one sieve goes: servers knocked, working configs sought, downloads measured. */
    private data class Depth(val knock: Int, val healthy: Int, val speedTested: Int, val windowMs: Long, val speedReserveMs: Long) {
        companion object {
            val QUICK = Depth(knock = 600, healthy = 30, speedTested = 8, windowMs = 3_500, speedReserveMs = 35_000)
        }
    }

    companion object {
        const val TTL_MS = 24 * 3_600_000L
        const val REFRESH_MS = 3 * 3_600_000L
        const val FAILED_FOR_MS = 30 * 60_000L
        const val MIN_HEALTHY = 3
        private const val KNOCK_MAX = 4_000
        private const val BATCH = 80
        private const val KNOCK_BUDGET_MS = 20_000L

        /** A download this quick carries reels without stalling. */
        private const val FAST_KBPS = 400L
        private val DEEP_WANT = Depth(knock = 1_200, healthy = 60, speedTested = 12, windowMs = 5_000, speedReserveMs = 70_000)
        private const val MIN_TEST_MS = 60_000L
        private const val MAX_POOLED = 60
        private const val MAX_FAILED_REPORTS = 40
    }
}
