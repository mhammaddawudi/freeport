package io.github.mhammaddawudi.freeport.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Asks Cloudflare which country the session leaves from.
 *
 * `cdn-cgi/trace` reports the country Cloudflare places the visiting address
 * in — the same judgement the many services behind Cloudflare, ChatGPT among
 * them, use to decide whether to serve a region.
 */
object ExitLocator {

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val TIMEOUT_MS = 10_000

    /** The two-letter exit country, or null when the lookup did not answer. */
    suspend fun country(socksPort: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val connection = URL(TRACE_URL).openConnection(proxy) as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.inputStream.bufferedReader().use { parse(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun parse(trace: String): String? = trace.lineSequence()
        .firstOrNull { it.startsWith("loc=") }
        ?.removePrefix("loc=")
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.length == 2 }
}
