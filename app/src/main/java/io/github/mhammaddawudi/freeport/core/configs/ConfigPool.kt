package io.github.mhammaddawudi.freeport.core.configs

import io.github.mhammaddawudi.freeport.core.CoreLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The configs that work on each network, fastest first. Built by sieving the
 * public collections ([refresh]) and kept on disk, so the next connection on
 * the same network goes straight to a known good one.
 */
class ConfigPool(
    private val folder: File,
    private val feed: ConfigFeed,
    private val tester: ConfigTester,
    private val log: CoreLog = CoreLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val link: ShareLink, val delayMs: Long, val kbps: Long, val at: Long, val failedAt: Long = 0)

    private val file = File(folder, "pool.json")
    private val pools: MutableMap<String, List<Entry>> = load()

    /** Configs to try on [network], best first; empty when the pool needs a [refresh]. */
    @Synchronized
    fun candidates(network: String): List<ShareLink> {
        val now = clock()
        return pools[network].orEmpty()
            .filter { now - it.at < TTL_MS && now - it.failedAt > FAILED_FOR_MS }
            .sortedWith(compareByDescending<Entry> { it.kbps }.thenBy { it.delayMs })
            .map { it.link }
    }

    /** Whether [network]'s pool is thin or old enough to be rebuilt. */
    fun isStale(network: String): Boolean {
        val entries = pools[network].orEmpty()
        val newest = entries.maxOfOrNull { it.at } ?: 0
        return candidates(network).size < MIN_HEALTHY || clock() - newest > REFRESH_MS
    }

    @Synchronized
    fun markFailed(network: String, link: ShareLink) {
        pools[network] = pools[network].orEmpty().map { if (it.link.key == link.key) it.copy(failedAt = clock()) else it }
        save()
    }

    /**
     * Sieves the collections for configs that work here: TCP knock, a real
     * request through each, then the download of the quickest few.
     */
    suspend fun refresh(network: String, budgetMs: Long, via: suspend () -> List<Int> = { emptyList() }): Int = withContext(Dispatchers.Default) {
        val begun = clock()
        val known = pools[network].orEmpty().filter { clock() - it.failedAt > FAILED_FOR_MS }.map { it.link }
        val fresh = feed.load(via).shuffled()
        if (fresh.isEmpty() && known.isEmpty()) return@withContext 0
        val keys = known.map { it.key }.toSet()
        val pile = known + fresh.filter { it.key !in keys }.take(KNOCK_MAX)
        // Fetching may have taken a while; testing gets what is left, and at least its share.
        val deadline = maxOf(begun + budgetMs, clock() + MIN_TEST_MS)
        log.info("[*] configs: knocking on ${pile.size} servers")
        val open = tester.knock(pile, KNOCK_WANT, KNOCK_BUDGET_MS)
        log.info("[*] configs: ${open.size} servers answered; testing them through xray")

        val healthy = mutableListOf<ConfigTester.Healthy>()
        for (batch in open.chunked(BATCH)) {
            if (healthy.size >= WANT_HEALTHY || clock() > deadline - SPEED_RESERVE_MS) break
            healthy += tester.probe(batch)
            log.info("[*] configs: ${healthy.size} working so far")
        }
        if (healthy.isEmpty()) {
            log.warn("[-] configs: none of the public configs carries traffic on this network")
            return@withContext 0
        }
        val quickest = healthy.sortedBy { it.delayMs }
        val measured = tester.measure(quickest.take(SPEED_TESTED)) + quickest.drop(SPEED_TESTED)
        val now = clock()
        synchronized(this@ConfigPool) {
            pools[network] = measured.map { Entry(it.link, it.delayMs, it.kbps, now) }
            save()
        }
        log.good("[+] configs: ${measured.size} working configs pooled, best ${measured.maxOf { it.kbps }} KB/s")
        measured.size
    }

    private fun load(): MutableMap<String, List<Entry>> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { network ->
            val list = json.getJSONArray(network)
            (0 until list.length()).mapNotNull { i ->
                val item = list.getJSONObject(i)
                ShareLink.parse(item.getString("link"))?.let {
                    Entry(it, item.getLong("delay"), item.getLong("kbps"), item.getLong("at"), item.optLong("failed"))
                }
            }
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun save() {
        val json = JSONObject()
        pools.forEach { (network, entries) ->
            json.put(
                network,
                JSONArray(
                    entries.map {
                        JSONObject().put("link", it.link.raw).put("delay", it.delayMs).put("kbps", it.kbps).put("at", it.at).put("failed", it.failedAt)
                    },
                ),
            )
        }
        runCatching {
            folder.mkdirs()
            file.writeText(json.toString())
        }
    }

    companion object {
        const val TTL_MS = 24 * 3_600_000L
        const val REFRESH_MS = 3 * 3_600_000L
        const val FAILED_FOR_MS = 30 * 60_000L
        const val MIN_HEALTHY = 3
        private const val KNOCK_MAX = 3_000
        private const val KNOCK_WANT = 480
        private const val BATCH = 80
        private const val WANT_HEALTHY = 24
        private const val SPEED_TESTED = 6
        private const val SPEED_RESERVE_MS = 20_000L
        private const val KNOCK_BUDGET_MS = 15_000L
        private const val MIN_TEST_MS = 60_000L
    }
}
