package io.github.mhammaddawudi.freeport.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * How fast an edge actually delivers, measured through the session's proxy.
 *
 * Ping picks edges that answer quickly, but on Iranian networks a quick edge
 * is regularly a throttled one: the handshake goes through and the bulk data
 * crawls. A short download is the only honest test. It stops after
 * [WINDOW_MS] or [BYTES], whichever comes first, so it costs at most a couple
 * of megabytes once per connection.
 */
object ThroughputProbe {

    private const val BYTES = 4_000_000
    private const val URL_DOWN = "https://speed.cloudflare.com/__down?bytes=$BYTES"
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val WINDOW_MS = 4_000L

    /** The first stretch includes TCP slow start; the rate is taken after it. */
    private const val WARMUP_BYTES = 64 * 1024

    /** Kilobytes per second, or null when nothing came through. */
    suspend fun measure(socksPort: Int): Long? = withContext(Dispatchers.IO) {
        runCatching { download(socksPort) }.getOrNull()
    }

    private fun download(socksPort: Int): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val connection = URL(URL_DOWN).openConnection(proxy) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = WINDOW_MS.toInt()
            connection.useCaches = false
            if (connection.responseCode !in 200..299) throw IOException("answered ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                var warmAt = -1L
                var warmBytes = 0L
                val deadline = SystemClock.elapsedRealtime() + WINDOW_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (warmAt < 0 && total >= WARMUP_BYTES) {
                        warmAt = SystemClock.elapsedRealtime()
                        warmBytes = total
                    }
                }
                rate(total - warmBytes, warmAt)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun rate(bytes: Long, since: Long): Long? {
        if (since < 0) return 0
        val elapsed = SystemClock.elapsedRealtime() - since
        if (elapsed <= 0 || bytes <= 0) return null
        return bytes * 1000 / elapsed / 1024
    }
}
