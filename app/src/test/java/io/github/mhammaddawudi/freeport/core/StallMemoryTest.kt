package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallMemoryTest {

    @Test
    fun `a stall is remembered for a few hours, then forgotten`() {
        val at = 1_000_000L
        assertTrue(StallMemory.isRecent(at, at + 60_000))
        assertFalse(StallMemory.isRecent(at, at + StallMemory.MEMORY_MS))
        assertFalse(StallMemory.isRecent(0, at))
    }

    @Test
    fun `stalled protocols go last and the rest keep their order`() {
        val known = listOf(
            Protocol.WireGuard to "wg-fast",
            Protocol.MasqueH2 to "h2",
            Protocol.WireGuard to "wg-slow",
            Protocol.MasqueH3 to "h3",
        )
        val ordered = StallMemory.stalledLast(known, setOf(Protocol.WireGuard)) { it.first }
        assertEquals(listOf("h2", "h3", "wg-fast", "wg-slow"), ordered.map { it.second })
    }

    @Test
    fun `with nothing stalled the order is untouched`() {
        val known = listOf(Protocol.WireGuard, Protocol.MasqueH2)
        assertEquals(known, StallMemory.stalledLast(known, emptySet()) { it })
    }
}
