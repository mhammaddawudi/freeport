package io.github.mhammaddawudi.freeport.core

/** An edge in the pool, and when it was last measured or found. */
data class PooledEdge(val edge: SharedEdge, val savedAt: Long) {
    /** Found by a scan but not yet carried any data; the scout measures these next. */
    val measured: Boolean get() = edge.kbps > 0
    val socket: String get() = edge.endpoint.socket
}

/**
 * The edges this phone has found and measured on one network, for one
 * protocol and address family.
 *
 * One remembered edge per protocol used to be all there was, and every edge
 * the scout measured overwrote the last, so the list never grew. The pool
 * keeps up to [MAX] of them, measured ones first and fastest first, plus the
 * runners-up of scans waiting for the scout to measure them. Pure functions
 * over an immutable list, so it can be tested without Android.
 */
object EdgePool {
    const val MAX = 32

    /** Unmeasured candidates kept at most; they never crowd out measured edges. */
    const val MAX_UNMEASURED = 12

    /** A measurement older than this says little about the line today. */
    const val TTL_MS = 2 * 24 * 60 * 60 * 1000L

    /** Adds or replaces [added] (same address and port), then drops what expired and trims. */
    fun merge(current: List<PooledEdge>, added: PooledEdge, now: Long): List<PooledEdge> {
        val previous = current.firstOrNull { it.socket == added.socket }
        // A scan finding an edge again says nothing about its speed: keep the measurement.
        val kept = if (!added.measured && previous != null && previous.measured) {
            previous.copy(savedAt = added.savedAt)
        } else {
            added
        }
        return tidy(current.filter { it.socket != added.socket } + kept, now)
    }

    fun mergeAll(current: List<PooledEdge>, added: List<PooledEdge>, now: Long): List<PooledEdge> =
        added.fold(current) { pool, edge -> merge(pool, edge, now) }

    fun without(current: List<PooledEdge>, socket: String): List<PooledEdge> =
        current.filter { it.socket != socket }

    /** Measured edges first, by how fast a reel would start; then the candidates, quickest ping first. */
    fun tidy(current: List<PooledEdge>, now: Long): List<PooledEdge> {
        val live = current.filter { now - it.savedAt in 0..TTL_MS }
        val measured = live.filter { it.measured }.sortedByDescending { it.edge.kbps }
        val waiting = live.filter { !it.measured }.sortedBy { it.edge.endpoint.rttMs }.take(MAX_UNMEASURED)
        return (measured + waiting).take(MAX)
    }

    /** `ip|port|rtt|kbps|up|savedAt` per edge, `;` between them; the ip may be IPv6. */
    fun encode(pool: List<PooledEdge>): String = pool.joinToString(";") { pooled ->
        val e = pooled.edge
        listOf(e.endpoint.ip, e.endpoint.port, e.endpoint.rttMs, e.kbps, e.upKbps, pooled.savedAt).joinToString("|")
    }

    fun decode(raw: String?): List<PooledEdge> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { item ->
            val parts = item.split('|')
            if (parts.size != 6 || parts[0].isBlank()) return@mapNotNull null
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            val numbers = parts.drop(2).map { it.toLongOrNull() ?: return@mapNotNull null }
            PooledEdge(SharedEdge(Endpoint(parts[0], port, numbers[0]), numbers[1], numbers[2]), numbers[3])
        }
    }
}
