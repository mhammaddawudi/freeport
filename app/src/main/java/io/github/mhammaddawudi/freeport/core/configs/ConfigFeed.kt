package io.github.mhammaddawudi.freeport.core.configs

import io.github.mhammaddawudi.freeport.core.CoreLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The public V2Ray collections on GitHub, refreshed by their owners every few
 * hours. Each is fetched from GitHub directly or, where GitHub is filtered,
 * through jsDelivr's mirror of it; the last copy is kept, so a network that
 * blocks both still has configs to test.
 */
class ConfigFeed(
    private val folder: File,
    private val log: CoreLog = CoreLog,
    /** Try the daily tested list first; off for the census that makes it. */
    private val includeCensus: Boolean = true,
    /** The edge board (server/edge-board), which keeps the pool every phone's tests feed; empty for none. */
    private val board: String = "",
) {
    /** One config's test on this phone, for the shared pool. */
    data class Report(val link: ShareLink, val ok: Boolean, val delayMs: Long = 0, val kbps: Long = 0)


    /** A file in a public repository. */
    data class Source(val repo: String, val branch: String, val path: String) {
        val mirrors: List<String>
            get() = listOf(
                "https://raw.githubusercontent.com/$repo/$branch/$path",
                "https://cdn.jsdelivr.net/gh/$repo@$branch/$path",
                "https://fastly.jsdelivr.net/gh/$repo@$branch/$path",
            )
        val id: String get() = "$repo/$path".replace(Regex("[^A-Za-z0-9]+"), "_")
    }

    private val cache = File(folder, "feed.txt")

    /** Whether the kept copy is missing or older than [MAX_AGE_MS]. */
    val isStale: Boolean get() = !cache.isFile || System.currentTimeMillis() - cache.lastModified() > MAX_AGE_MS

    val hasCopy: Boolean get() = cache.isFile && cache.length() > 0

    /**
     * Configs from every source, fresh when the kept copy is stale. GitHub is
     * tried over the bare line, then through each local SOCKS port [via]
     * opens, which is only asked for when the bare line fails.
     */
    suspend fun load(via: suspend () -> List<Int> = { emptyList() }): List<ShareLink> = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val bodies = if (isStale) fetchAll(via = via) else emptyList()
        if (bodies.isNotEmpty()) {
            runCatching { cache.writeText(bodies.joinToString("\n")) }
        }
        val text = bodies.joinToString("\n").ifEmpty { runCatching { cache.readText() }.getOrDefault("") }
        val links = ShareLink.parseAll(text).distinctBy { it.key }
        log.info("[*] configs: ${links.size} usable configs${if (bodies.isEmpty()) " (kept copy)" else ""}")
        links
    }

    /** Refreshes the kept copy through a tunnel that is up anyway, when it is stale. */
    suspend fun refreshThrough(socksPort: Int) = withContext(Dispatchers.IO) {
        if (!isStale) return@withContext
        val bodies = fetchAll(bareLine = false) { listOf(socksPort) }
        if (bodies.isNotEmpty()) runCatching { cache.writeText(bodies.joinToString("\n")) }
    }

    private suspend fun fetchAll(bareLine: Boolean = true, via: suspend () -> List<Int>): List<String> {
        if (bareLine) {
            val bodies = fetchVia(null)
            if (bodies.isNotEmpty()) return bodies
            log.info("[*] configs: GitHub is out of reach on the bare line")
        }
        for (route in via()) {
            val bodies = fetchVia(route)
            if (bodies.isNotEmpty()) return bodies
            log.info("[*] configs: GitHub is out of reach through port $route")
        }
        return emptyList()
    }

    /**
     * The census first: a short list tested daily, enough on its own. The
     * collections themselves, megabytes each, only when it is out of reach.
     */
    /**
     * Best first: the shared pool on the board (what Freeport users found,
     * then the census), else the census from GitHub, else the collections
     * themselves, shuffled so phones do not all knock on the same few.
     */
    private suspend fun fetchVia(route: Int?): List<String> {
        if (includeCensus) {
            if (board.isNotEmpty()) {
                val shared = runCatching { get("$board/v1/configs", route) }.getOrNull()
                    ?.let { body -> ShareLink.parseAll(body).joinToString("\n") { it.raw } }
                if (shared != null && shared.lines().size >= MIN_CENSUS) {
                    log.info("[+] configs: the shared pool${if (route == null) "" else " through port $route"}")
                    return listOf(shared)
                }
            }
            fetch(CENSUS, route)?.takeIf { it.lines().size >= MIN_CENSUS }?.let { return listOf(it) }
        }
        val bodies = coroutineScope { SOURCES.map { source -> async { fetch(source, route) } }.awaitAll().filterNotNull() }
        return if (bodies.isEmpty()) bodies else listOf(bodies.flatMap { it.lines() }.shuffled().joinToString("\n"))
    }

    /** Sends this phone's results to the shared pool; false when the board was out of reach. */
    suspend fun share(reports: List<Report>, socksPort: Int? = null): Boolean = withContext(Dispatchers.IO) {
        if (board.isEmpty() || reports.isEmpty()) return@withContext true
        val body = org.json.JSONObject().put(
            "results",
            org.json.JSONArray(
                reports.take(MAX_REPORT).map {
                    org.json.JSONObject().put("link", it.link.raw).put("ok", it.ok).put("delay", it.delayMs).put("kbps", it.kbps)
                },
            ),
        ).toString()
        runCatching {
            val proxy = socksPort?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", it)) } ?: Proxy.NO_PROXY
            val connection = (URL("$board/v1/configs").openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun fetch(source: Source, socksPort: Int?): String? {
        for (url in source.mirrors) {
            val body = runCatching { get(url, socksPort) }.getOrNull()
            if (body != null && body.length > MIN_BODY) {
                log.info("[+] configs: ${source.repo} via ${URL(url).host}")
                return ShareLink.parseAll(body).joinToString("\n") { it.raw }
            }
        }
        return null
    }

    private fun get(url: String, socksPort: Int?): String? {
        val proxy = socksPort?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", it)) } ?: Proxy.NO_PROXY
        val connection = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = if (socksPort == null) BARE_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The configs that answered in the daily census, quickest first (`.github/workflows/configs.yml`). */
        val CENSUS = Source("mhammaddawudi/freeport", "configs", "alive.txt")
        private const val MIN_CENSUS = 50

        /** Large, frequently refreshed, mixed-protocol collections. */
        val SOURCES = listOf(
            Source("barry-far/V2ray-Config", "main", "All_Configs_Sub.txt"),
            Source("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/all_sub.txt"),
            Source("10ium/ScrapeAndCategorize", "main", "output_configs/Vless.txt"),
            Source("mahdibland/V2RayAggregator", "master", "sub/sub_merge.txt"),
            Source("roosterkid/openproxylist", "main", "V2RAY_RAW.txt"),
            Source("Kwinshadow/TelegramV2rayCollector", "main", "sublinks/mix.txt"),
        )
        const val MAX_AGE_MS = 3 * 3_600_000L
        private const val MIN_BODY = 200
        private const val MAX_REPORT = 80
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val BARE_CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 25_000
    }
}
