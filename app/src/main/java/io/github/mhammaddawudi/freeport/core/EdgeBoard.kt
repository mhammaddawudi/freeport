package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.content.SharedPreferences
import io.github.mhammaddawudi.freeport.data.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/** An edge someone on the same operator measured, with how well it did. */
data class SharedEdge(val endpoint: Endpoint, val kbps: Long)

/**
 * Edges that worked well for other people on the same operator.
 *
 * Every connection measures its edge's real download speed and reports it;
 * the board keeps the best per operator, protocol and address family. A
 * newcomer on the same operator then tries those first instead of scanning
 * from nothing, and gets an edge that is known to carry video.
 *
 * The board is often blocked from inside Iran, so it is reached two ways:
 * directly before connecting (it rarely works, but costs little), and through
 * the session's own proxy once connected ([socksPort] > 0). The list fetched
 * while connected is kept on the phone and is what the next connection on the
 * same operator starts from.
 */
interface EdgeBoard {
    suspend fun fetch(protocol: Protocol, ipFamily: String, network: String, socksPort: Int = 0): List<SharedEdge>
    suspend fun report(protocol: Protocol, ipFamily: String, network: String, edge: SharedEdge, socksPort: Int = 0)
}

object NoEdgeBoard : EdgeBoard {
    override suspend fun fetch(protocol: Protocol, ipFamily: String, network: String, socksPort: Int) =
        emptyList<SharedEdge>()

    override suspend fun report(protocol: Protocol, ipFamily: String, network: String, edge: SharedEdge, socksPort: Int) =
        Unit
}

class HttpEdgeBoard(
    context: Context,
    private val baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: CoreLog = CoreLog,
) : EdgeBoard {

    private val saved: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The board's answer, or the last one kept for this operator when it cannot be reached. */
    override suspend fun fetch(protocol: Protocol, ipFamily: String, network: String, socksPort: Int): List<SharedEdge> =
        withContext(Dispatchers.IO) {
            val key = "$network.${protocol.id}.$ipFamily"
            val fresh = runCatching {
                val query = "p=${enc(protocol.id)}&ip=${enc(ipFamily)}&net=${enc(network)}"
                val connection = URL("$baseUrl/v1/edges?$query").openConnection(proxyOf(socksPort)) as HttpURLConnection
                try {
                    // Direct attempts are short: on a blocked network they only delay the connection.
                    val timeout = if (socksPort > 0) TIMEOUT_MS else DIRECT_TIMEOUT_MS
                    connection.connectTimeout = timeout
                    connection.readTimeout = timeout
                    if (connection.responseCode != 200) return@runCatching null
                    connection.inputStream.bufferedReader().use { it.readText() }.also { parse(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (fresh != null) {
                saved.edit().putString(key, fresh).putLong("$key.at", clock()).apply()
                return@withContext parse(fresh)
            }
            val kept = saved.getString(key, null)
                ?.takeIf { clock() - saved.getLong("$key.at", 0) < KEEP_MS }
                ?: return@withContext emptyList()
            runCatching { parse(kept) }.getOrDefault(emptyList())
        }

    override suspend fun report(protocol: Protocol, ipFamily: String, network: String, edge: SharedEdge, socksPort: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("p", protocol.id)
                    .put("ip", ipFamily)
                    .put("net", network)
                    .put("addr", edge.endpoint.ip)
                    .put("port", edge.endpoint.port)
                    .put("rtt", edge.endpoint.rttMs)
                    .put("kbps", edge.kbps)
                    .toString()
                    .toByteArray()
                val connection = URL("$baseUrl/v1/report").openConnection(proxyOf(socksPort)) as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body) }
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }.onFailure { log.info("[*] could not share the edge: ${it.message}") }
        }
    }

    private fun proxyOf(socksPort: Int): Proxy =
        if (socksPort > 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)) else Proxy.NO_PROXY

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val FILE = "aether.shared-edges"
        private const val TIMEOUT_MS = 5_000
        private const val DIRECT_TIMEOUT_MS = 1_500

        /** A kept list older than this says little about today's network. */
        private const val KEEP_MS = 3 * 24 * 60 * 60 * 1000L
        private const val MAX_EDGES = 6

        /** Anything off Cloudflare's edge ranges is dropped, whatever the board says. */
        internal fun parse(raw: String): List<SharedEdge> {
            val edges = JSONObject(raw).optJSONArray("edges") ?: JSONArray()
            return (0 until edges.length())
                .mapNotNull { index ->
                    val item = edges.optJSONObject(index) ?: return@mapNotNull null
                    val ip = item.optString("addr")
                    val port = item.optInt("port")
                    if (port !in 1..65535 || !isCloudflareEdge(ip)) return@mapNotNull null
                    SharedEdge(Endpoint(ip, port, item.optLong("rtt")), item.optLong("kbps"))
                }
                .take(MAX_EDGES)
        }

        /** 162.159.0.0/16, 188.114.96.0/22 and 2606:4700::/32, without resolving anything. */
        fun isCloudflareEdge(ip: String): Boolean {
            if (ip.isBlank() || !ip.all { it.isDigit() || it in "abcdefABCDEF.:" }) return false
            val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
            val b = bytes.map { it.toInt() and 0xFF }
            return when (b.size) {
                4 -> (b[0] == 162 && b[1] == 159) || (b[0] == 188 && b[1] == 114 && b[2] in 96..99)
                16 -> b[0] == 0x26 && b[1] == 0x06 && b[2] == 0x47 && b[3] == 0x00
                else -> false
            }
        }
    }
}
