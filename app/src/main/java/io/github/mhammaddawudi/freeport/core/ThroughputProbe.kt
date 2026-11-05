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
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val WINDOW_MS = 4_000L
    private const val URL_UP = "https://speed.cloudflare.com/__up"

    /** The first stretch includes TCP slow start; the rate is taken after it. */
    private const val WARMUP_BYTES = 64 * 1024

    /**
     * [kbps] is the download rate. [firstByteMs] is how long a brand-new
     * connection took to deliver its first byte — tunnel handshake, TLS and
     * request included — which is what each new reel or image waits for.
     * [upKbps] is the upload rate, 0 when it was not measured.
     */
    data class Measurement(val kbps: Long, val firstByteMs: Long, val upKbps: Long = 0)

    /** Null when nothing came through. [upBytes] > 0 also measures the upload. */
    suspend fun measure(
        socksPort: Int,
        bytes: Int = BYTES,
        windowMs: Long = WINDOW_MS,
        upBytes: Int = 0,
    ): Measurement? = withContext(Dispatchers.IO) {
        val down = runCatching { download(socksPort, bytes, windowMs) }.getOrNull() ?: return@withContext null
        if (upBytes <= 0) return@withContext down
        down.copy(upKbps = runCatching { upload(socksPort, upBytes, windowMs) }.getOrNull() ?: 0)
    }

    /**
     * Uploads [bytes] and times it until the far end answers. Timing the
     * writes alone would measure the tunnel's local buffers, which swallow
     * half a megabyte at once.
     */
    suspend fun upload(socksPort: Int, bytes: Int, windowMs: Long = WINDOW_MS): Long? = withContext(Dispatchers.IO) {
        runCatching { send(socksPort, bytes, windowMs) }.getOrNull()
    }

    private fun send(socksPort: Int, bytes: Int, windowMs: Long): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val connection = URL(URL_UP).openConnection(proxy) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = windowMs.toInt()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes)
            val chunk = ByteArray(16 * 1024)
            var started = 0L
            connection.outputStream.use { output ->
                // Opening the stream sets up TCP and TLS through the tunnel;
                // the clock starts once the first chunk is on its way.
                output.write(chunk, 0, minOf(chunk.size, bytes))
                started = SystemClock.elapsedRealtime()
                var sent = minOf(chunk.size, bytes)
                while (sent < bytes) {
                    val size = minOf(chunk.size, bytes - sent)
                    output.write(chunk, 0, size)
                    sent += size
                }
            }
            if (connection.responseCode !in 200..299) return null
            return rate((bytes - minOf(chunk.size, bytes)).toLong(), started)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(socksPort: Int, bytes: Int, windowMs: Long): Measurement? {
        val opened = SystemClock.elapsedRealtime()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val connection = URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection(proxy) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = windowMs.toInt()
            connection.useCaches = false
            if (connection.responseCode !in 200..299) throw IOException("answered ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                var warmAt = -1L
                var warmBytes = 0L
                var firstAt = -1L
                val deadline = SystemClock.elapsedRealtime() + windowMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (firstAt < 0) firstAt = SystemClock.elapsedRealtime()
                    total += read
                    if (warmAt < 0 && total >= WARMUP_BYTES) {
                        warmAt = SystemClock.elapsedRealtime()
                        warmBytes = total
                    }
                }
                if (firstAt < 0) return@use null
                val kbps = rate(total - warmBytes, warmAt) ?: return@use null
                Measurement(kbps, firstAt - opened)
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
