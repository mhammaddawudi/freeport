package io.github.mhammaddawudi.freeport.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Knocks on known TCP edges all at once and keeps the ones that open.
 *
 * Trying a dead edge through the full tunnel costs seconds; a TCP connect
 * costs one round trip. Only meaningful for MASQUE over HTTP/2, which rides
 * TCP — UDP edges cannot be checked this cheaply.
 */
object EdgeReach {

    private const val TIMEOUT_MS = 2_000

    /**
     * The edges that accepted a connection, in the order given — the caller
     * has already ranked them by speed, which a connect time says nothing about.
     */
    suspend fun reachable(edges: List<Endpoint>): List<Endpoint> = coroutineScope {
        edges.map { edge -> async(Dispatchers.IO) { edge to knock(edge) } }
            .awaitAll()
            .filter { (_, ms) -> ms != null }
            .map { (edge, _) -> edge }
    }

    /** The quickest TCP connect among [edges], all knocked at once: a round trip, about. */
    suspend fun quickest(edges: List<Endpoint>): Long? = coroutineScope {
        edges.map { edge -> async(Dispatchers.IO) { knock(edge) } }.awaitAll().filterNotNull().minOrNull()
    }

    private fun knock(edge: Endpoint): Long? = runCatching {
        val started = SystemClock.elapsedRealtime()
        Socket().use { it.connect(InetSocketAddress(edge.ip, edge.port), TIMEOUT_MS) }
        SystemClock.elapsedRealtime() - started
    }.getOrNull()
}
