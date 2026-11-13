package io.github.mhammaddawudi.freeport.core.configs

import io.github.mhammaddawudi.freeport.core.CoreLog
import io.github.mhammaddawudi.freeport.core.ThroughputProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Finds which public configs work on this network, and how fast.
 *
 * Three sieves, each dearer than the last: a TCP knock on the server, which
 * drops dead servers by the thousand for free; a real request through each
 * config, a whole batch under one Xray; and a download through the quickest
 * few, which is what the pool is finally ordered by.
 */
class ConfigTester(private val xray: Xray, private val log: CoreLog = CoreLog) {

    /** A config that carried a real request, [delayMs] to the answer, [kbps] when measured. */
    data class Healthy(val link: ShareLink, val delayMs: Long, val kbps: Long = 0)

    /**
     * Servers that accept a TCP connection, quickest first: at most [want],
     * whatever answered within [budgetMs]. A name lookup cannot be cut short,
     * so knocks still running then are left to finish on their own.
     */
    suspend fun knock(links: List<ShareLink>, want: Int, budgetMs: Long): List<ShareLink> {
        val gate = Semaphore(KNOCK_PARALLEL)
        val open = java.util.concurrent.ConcurrentHashMap<ShareLink, Long>()
        val knocking = CoroutineScope(Dispatchers.IO + SupervisorJob())
        links.forEach { link ->
            knocking.launch {
                gate.withPermit {
                    if (open.size >= want) return@withPermit
                    val started = System.nanoTime()
                    val reached = runCatching {
                        Socket().use { it.connect(InetSocketAddress(link.host, link.port), KNOCK_TIMEOUT_MS) }
                    }.isSuccess
                    if (reached) open[link] = (System.nanoTime() - started) / 1_000_000
                }
            }
        }
        val all = knocking.coroutineContext[Job]!!
        withTimeoutOrNull(budgetMs) {
            while (open.size < want && all.children.any { it.isActive }) delay(100)
        }
        knocking.cancel()
        return open.entries.sortedBy { it.value }.map { it.key }.take(want)
    }

    /** Sends a real request through every config in [links], one Xray for the batch. */
    suspend fun probe(links: List<ShareLink>): List<Healthy> {
        if (links.isEmpty()) return emptyList()
        val routes = freePorts(links.size).zip(links) { port, link -> Xray.Route(link, port) }
        val running = xray.start(routes, "batch").getOrElse { error ->
            // One config Xray does not accept fails the batch: halve it until it is found.
            if (links.size == 1) return emptyList()
            log.info("[*] configs: xray refused a batch of ${links.size} (${error.message?.take(120)}), splitting it")
            val half = links.size / 2
            return probe(links.take(half)) + probe(links.drop(half))
        }
        return running.use {
            coroutineScope {
                routes.map { route ->
                    async(Dispatchers.IO) { answers(route.port)?.let { Healthy(route.link, it) } }
                }.awaitAll().filterNotNull()
            }
        }
    }

    /** Measures the download through each of [healthy], one at a time so they do not share the line. */
    suspend fun measure(healthy: List<Healthy>): List<Healthy> {
        if (healthy.isEmpty()) return emptyList()
        val routes = freePorts(healthy.size).zip(healthy) { port, h -> Xray.Route(h.link, port) }
        val running = xray.start(routes, "speed").getOrElse { return healthy }
        return running.use {
            routes.zip(healthy) { route, h ->
                val kbps = ThroughputProbe.measure(route.port, SPEED_BYTES, SPEED_WINDOW_MS)?.kbps ?: 0
                log.info("[*] configs: ${h.link.label} carries $kbps KB/s (${h.delayMs}ms)")
                h.copy(kbps = kbps)
            }
        }
    }

    /**
     * A plain HTTP request through the SOCKS port, timed to its answer. Xray
     * grants a SOCKS connect before it reaches anything, so only an answer
     * from the far side says the config works.
     */
    fun answers(port: Int): Long? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = PROBE_TIMEOUT_MS
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            val started = System.nanoTime()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            input.readFully(ByteArray(2))
            val host = PROBE_HOST.toByteArray(Charsets.US_ASCII)
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host + byteArrayOf(0x00, 0x50))
            val reply = ByteArray(10).also { input.readFully(it) }
            if (reply[1] != 0x00.toByte()) return@use null
            output.write("GET /generate_204 HTTP/1.1\r\nHost: $PROBE_HOST\r\nConnection: close\r\n\r\n".toByteArray())
            val status = input.bufferedReader().readLine().orEmpty()
            if (status.startsWith("HTTP/") && (status.contains(" 204") || status.contains(" 200"))) {
                (System.nanoTime() - started) / 1_000_000
            } else {
                null
            }
        }
    }.getOrNull()

    companion object {
        const val PROBE_HOST = "cp.cloudflare.com"
        private const val KNOCK_PARALLEL = 256
        private const val KNOCK_TIMEOUT_MS = 2_000
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val SPEED_BYTES = 1_500_000
        private const val SPEED_WINDOW_MS = 3_000L

        /** [count] ports nothing listens on right now. */
        fun freePorts(count: Int): List<Int> {
            val sockets = List(count) { ServerSocket(0) }
            return sockets.map { it.localPort }.also { sockets.forEach(ServerSocket::close) }
        }
    }
}

/** What the log calls a config: its kind and server, never its secret. */
val ShareLink.label: String get() = "${kind.name.lowercase()} ${host.take(40)}:$port ($network/$security)"
