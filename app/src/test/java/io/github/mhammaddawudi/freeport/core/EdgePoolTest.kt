package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgePoolTest {

    private fun edge(ip: String, kbps: Long, rtt: Long = 300, up: Long = 0) =
        SharedEdge(Endpoint(ip, 443, rtt), kbps, up)

    @Test
    fun `the pool grows instead of keeping one edge`() {
        val now = 1_000_000L
        val pool = EdgePool.mergeAll(
            emptyList(),
            listOf(PooledEdge(edge("162.159.1.1", 900), now), PooledEdge(edge("162.159.1.2", 2_000), now)),
            now,
        )
        assertEquals(2, pool.size)
        assertEquals("162.159.1.2", pool.first().edge.endpoint.ip)
    }

    @Test
    fun `measured edges rank above candidates waiting to be measured`() {
        val now = 1_000_000L
        val pool = EdgePool.mergeAll(
            emptyList(),
            listOf(PooledEdge(edge("162.159.2.1", 0, rtt = 40), now), PooledEdge(edge("162.159.2.2", 400), now)),
            now,
        )
        assertEquals(listOf("162.159.2.2", "162.159.2.1"), pool.map { it.edge.endpoint.ip })
    }

    @Test
    fun `finding an edge again by scan keeps its measured speed`() {
        val now = 1_000_000L
        val measured = EdgePool.merge(emptyList(), PooledEdge(edge("162.159.3.1", 1_500), now - 10), now)
        val rescanned = EdgePool.merge(measured, PooledEdge(edge("162.159.3.1", 0, rtt = 80), now), now)
        assertEquals(1, rescanned.size)
        assertEquals(1_500L, rescanned.single().edge.kbps)
        assertEquals(now, rescanned.single().savedAt)
    }

    @Test
    fun `old measurements expire and the pool is capped`() {
        val now = EdgePool.TTL_MS * 3
        val stale = PooledEdge(edge("162.159.4.1", 5_000), now - EdgePool.TTL_MS - 1)
        val many = (1..50).map { PooledEdge(edge("162.159.5.$it", 300L + it), now) }
        val pool = EdgePool.mergeAll(listOf(stale), many, now)
        assertEquals(EdgePool.MAX, pool.size)
        assertTrue(pool.none { it.edge.endpoint.ip == "162.159.4.1" })
    }

    @Test
    fun `candidates never crowd out measured edges`() {
        val now = 1_000_000L
        val candidates = (1..30).map { PooledEdge(edge("162.159.6.$it", 0, rtt = it.toLong()), now) }
        val pool = EdgePool.mergeAll(listOf(PooledEdge(edge("162.159.7.1", 700), now)), candidates, now)
        assertEquals(1 + EdgePool.MAX_UNMEASURED, pool.size)
        assertTrue(pool.first().measured)
    }

    @Test
    fun `the pool survives being written and read back, ipv6 included`() {
        val now = 42L
        val pool = listOf(
            PooledEdge(edge("2606:4700:102::3", 1_200, rtt = 210, up = 300), now),
            PooledEdge(edge("188.114.97.6", 0, rtt = 95), now),
        )
        assertEquals(pool, EdgePool.decode(EdgePool.encode(pool)))
        assertEquals(emptyList<PooledEdge>(), EdgePool.decode("garbage;1|2"))
    }

    @Test
    fun `the fastest download leads even when its first byte and upload are slower`() {
        val now = 1L
        val pool = EdgePool.mergeAll(
            emptyList(),
            listOf(
                PooledEdge(edge("162.159.9.1", 900, rtt = 80, up = 900), now),
                PooledEdge(edge("162.159.9.2", 2_500, rtt = 600, up = 100), now),
            ),
            now,
        )
        assertEquals("162.159.9.2", pool.first().edge.endpoint.ip)
    }

    @Test
    fun `dropping an edge removes only that one`() {
        val now = 1L
        val pool = listOf(PooledEdge(edge("162.159.8.1", 500), now), PooledEdge(edge("162.159.8.2", 600), now))
        assertEquals(listOf("162.159.8.2"), EdgePool.without(pool, "162.159.8.1:443").map { it.edge.endpoint.ip })
    }
}
