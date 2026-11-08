package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedGuardTest {

    /** A clock the test moves, and a guard that started at its zero. */
    private class Rig(baseline: Long) {
        var clock = 0L
        var rx = 0L
        val guard = SpeedGuard(baseline, now = { clock }, startedAt = 0)

        /** [seconds] of traffic at [kbps], sampled once a second like the engine does. */
        fun run(seconds: Int, kbps: Long) {
            repeat(seconds) {
                clock += 1_000
                rx += kbps * 1024
                guard.feed(rx)
            }
        }
    }

    @Test
    fun `a session that slowed down while in use is suspected`() {
        val rig = Rig(baseline = 2_000)
        rig.run(70, 2_000)
        rig.run(50, 150)
        assertTrue(rig.guard.suspicious())
    }

    @Test
    fun `a fast session is left alone`() {
        val rig = Rig(baseline = 2_000)
        rig.run(120, 1_500)
        assertFalse(rig.guard.suspicious())
    }

    @Test
    fun `someone reading, not watching, is never judged`() {
        val rig = Rig(baseline = 2_000)
        rig.run(70, 2_000)
        rig.run(50, 10)
        assertFalse(rig.guard.suspicious())
    }

    @Test
    fun `reels arriving in fast bursts do not count as slow`() {
        val rig = Rig(baseline = 2_000)
        rig.run(70, 2_000)
        // A reel every ten seconds: two seconds of loading, then nothing.
        repeat(5) {
            rig.run(2, 1_800)
            rig.run(8, 0)
        }
        assertFalse(rig.guard.suspicious())
    }

    @Test
    fun `nothing is judged in the first minute`() {
        val rig = Rig(baseline = 2_000)
        rig.run(50, 100)
        assertFalse(rig.guard.suspicious())
    }

    @Test
    fun `after a check the next one waits`() {
        val rig = Rig(baseline = 2_000)
        rig.run(70, 2_000)
        rig.run(50, 150)
        assertTrue(rig.guard.suspicious())
        rig.run(10, 150)
        assertFalse(rig.guard.suspicious())
        rig.run(SpeedGuard.RECHECK_MS.toInt() / 1_000, 150)
        assertTrue(rig.guard.suspicious())
    }

    @Test
    fun `the floor never drops below what video needs`() {
        assertEquals(SpeedGuard.MIN_FLOOR_KBPS, SpeedGuard(400, now = { 0 }).floorKbps)
        assertEquals(1_400L, SpeedGuard(4_000, now = { 0 }).floorKbps)
        assertTrue(SpeedGuard(4_000, now = { 0 }).confirms(900))
        assertFalse(SpeedGuard(4_000, now = { 0 }).confirms(2_000))
    }
}
