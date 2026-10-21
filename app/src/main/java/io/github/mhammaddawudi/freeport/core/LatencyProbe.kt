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
 * Round-trip time through the tunnel, against Cloudflare's speed endpoint.
 *
 * The app is excluded from its own VPN, so the probe goes through the
 * session's SOCKS5 proxy — the path every tunnelled app takes. The first
 * request pays for TCP and TLS and is not counted; the median of the rest,
 * on the same kept-alive connection, is the ping.
 */
object LatencyProbe {

    private const val URL_ZERO_BYTES = "https://speed.cloudflare.com/__down?bytes=0"
    private const val SAMPLES = 4
    private const val TIMEOUT_MS = 8_000

    /** Milliseconds, or null when the probe did not get through. */
    suspend fun ping(socksPort: Int?): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = if (socksPort != null && socksPort > 0) {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            } else {
                Proxy.NO_PROXY
            }
            val samples = (0..SAMPLES).map { once(proxy) }.drop(1).sorted()
            samples[samples.size / 2]
        }.getOrNull()
    }

    private fun once(proxy: Proxy): Long {
        val started = SystemClock.elapsedRealtime()
        val connection = URL(URL_ZERO_BYTES).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        if (connection.responseCode !in 200..299) throw IOException("answered ${connection.responseCode}")
        // Read to the end without disconnect(), so the next sample reuses the socket.
        connection.inputStream.use { it.readBytes() }
        return SystemClock.elapsedRealtime() - started
    }
}
