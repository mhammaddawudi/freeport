package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.content.SharedPreferences
import io.github.mhammaddawudi.freeport.data.Protocol

/**
 * The last edge that carried a session well, per protocol.
 *
 * A fresh scan costs several seconds even when it goes well, and on most
 * days the edge that worked an hour ago still works. So the engine tries the
 * remembered one first with a short deadline, and only scans when it does
 * not come up. Only edges that held a session for a while are kept, and they
 * expire, so a route that has gone slow is not reused for long.
 */
interface EndpointCache {
    fun recall(key: String): Endpoint?
    fun remember(key: String, endpoint: Endpoint)
    fun forget(key: String)

    /** For automatic: which protocol it chose last, and on which edge. */
    fun recallAuto(): Pair<Protocol, Endpoint>?
    fun rememberAuto(protocol: Protocol, endpoint: Endpoint)
    fun forgetAuto()
}

class PrefsEndpointCache(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : EndpointCache {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun recall(key: String): Endpoint? {
        val raw = prefs.getString(key, null) ?: return null
        val entry = decode(raw) ?: return null
        if (clock() - entry.savedAt > ttlMs) {
            forget(key)
            return null
        }
        return entry.endpoint
    }

    override fun remember(key: String, endpoint: Endpoint) {
        prefs.edit().putString(key, encode(endpoint, clock())).apply()
    }

    override fun forget(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun recallAuto(): Pair<Protocol, Endpoint>? {
        val protocol = prefs.getString(KEY_AUTO_PROTOCOL, null)
            ?.let { id -> Protocol.entries.firstOrNull { it.id == id } }
            ?: return null
        val endpoint = recall(KEY_AUTO_ENDPOINT) ?: return null
        return protocol to endpoint
    }

    override fun rememberAuto(protocol: Protocol, endpoint: Endpoint) {
        prefs.edit()
            .putString(KEY_AUTO_PROTOCOL, protocol.id)
            .putString(KEY_AUTO_ENDPOINT, encode(endpoint, clock()))
            .apply()
    }

    override fun forgetAuto() {
        prefs.edit().remove(KEY_AUTO_PROTOCOL).remove(KEY_AUTO_ENDPOINT).apply()
    }

    private data class Entry(val endpoint: Endpoint, val savedAt: Long)

    companion object {
        private const val FILE = "aether.endpoints"
        private const val KEY_AUTO_PROTOCOL = "auto.protocol"
        private const val KEY_AUTO_ENDPOINT = "auto.endpoint"

        /** Long enough to cover a day of reconnects, short enough to rescan daily. */
        const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L

        /** `ip|port|rtt|savedAt` — the ip may be IPv6, so no colons as separators. */
        internal fun encode(endpoint: Endpoint, savedAt: Long): String =
            listOf(endpoint.ip, endpoint.port, endpoint.rttMs, savedAt).joinToString("|")

        private fun decode(raw: String): Entry? {
            val parts = raw.split('|')
            if (parts.size != 4) return null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val rtt = parts[2].toLongOrNull() ?: return null
            val savedAt = parts[3].toLongOrNull() ?: return null
            if (parts[0].isBlank()) return null
            return Entry(Endpoint(parts[0], port, rtt), savedAt)
        }

        /** One slot per protocol and address family: a v4 edge is no use to a v6 scan. */
        fun keyOf(protocol: Protocol, ipFamily: String): String = "${protocol.id}.$ipFamily"
    }
}

/** Remembers nothing; for tests and for the simulated core. */
object NoEndpointCache : EndpointCache {
    override fun recall(key: String): Endpoint? = null
    override fun remember(key: String, endpoint: Endpoint) = Unit
    override fun forget(key: String) = Unit
    override fun recallAuto(): Pair<Protocol, Endpoint>? = null
    override fun rememberAuto(protocol: Protocol, endpoint: Endpoint) = Unit
    override fun forgetAuto() = Unit
}
