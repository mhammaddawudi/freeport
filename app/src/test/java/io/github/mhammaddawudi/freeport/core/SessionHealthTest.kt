package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.core.PsiphonRelay.Health
import io.github.mhammaddawudi.freeport.core.PsiphonRelay.Tunnel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class SessionHealthTest {

    private class FakeRelay(
        override var health: Health = Health.Up,
        override var isStarting: Boolean = false,
        override val country: String = "DE",
    ) : UnlockRelay

    /** A session whose clock moves only when the test says so. */
    private class Rig(relayed: Boolean = true) {
        var clock = 0L
        val relay = FakeRelay()
        val carrying = mutableMapOf(TUNNEL to true, RELAY to true)
        val restarts = mutableListOf<String>()
        var restartFails: String? = null
        var downs = 0
        var backs = 0
        val health = SessionHealth(
            tunnelPort = TUNNEL,
            relayPort = if (relayed) RELAY else null,
            relay = if (relayed) relay else null,
            restartRelay = { country -> restarts += country; restartFails },
            carries = { port -> carrying.getValue(port) },
            onUnlockDown = { downs++ },
            onUnlockBack = { backs++ },
            now = { clock },
        )

        /** Moves to the next probe and checks once. */
        fun probe(): String? = runBlocking {
            clock += SessionHealth.PROBE_EVERY_MS
            health.check()
        }
    }

    @Test
    fun `a healthy session is left alone`() {
        val rig = Rig()
        repeat(5) { assertNull(rig.probe()) }
        assertEquals(0, rig.downs)
        assertTrue(rig.restarts.isEmpty())
    }

    @Test
    fun `a tunnel that stops carrying ends the session after three misses`() {
        val rig = Rig(relayed = false)
        rig.carrying[TUNNEL] = false
        assertNull(rig.probe())
        assertNull(rig.probe())
        assertEquals("the tunnel stopped carrying traffic", rig.probe())
    }

    @Test
    fun `one recovered probe clears the tunnel misses`() {
        val rig = Rig(relayed = false)
        rig.carrying[TUNNEL] = false
        rig.probe()
        rig.probe()
        rig.carrying[TUNNEL] = true
        assertNull(rig.probe())
        rig.carrying[TUNNEL] = false
        assertNull(rig.probe())
    }

    @Test
    fun `a psiphon that is alive but silent is restarted at once, towards the same country`() {
        val rig = Rig()
        rig.carrying[RELAY] = false
        assertNull(rig.probe())
        assertTrue(rig.restarts.isEmpty())
        assertNull(rig.probe())
        assertEquals(listOf("DE"), rig.restarts)
        assertEquals(1, rig.downs)
        assertEquals(1, rig.backs)
    }

    @Test
    fun `a psiphon that cannot be restarted ends the session`() {
        val rig = Rig()
        rig.carrying[RELAY] = false
        rig.restartFails = "psiphon found no server within 75s"
        rig.probe()
        assertEquals("region unlock could not come back: psiphon found no server within 75s", rig.probe())
        assertEquals(0, rig.backs)
    }

    @Test
    fun `psiphon finding another server on its own needs no restart`() {
        val rig = Rig()
        rig.relay.health = Health.Searching
        // It is back by the time the regain loop looks.
        rig.relay.health = Health.Up
        assertNull(runBlocking { rig.health.check() })
        rig.relay.health = Health.Searching
        thread { Thread.sleep(50); rig.relay.health = Health.Up }
        assertNull(runBlocking { rig.health.check() })
        assertTrue(rig.restarts.isEmpty())
        assertEquals(1, rig.backs)
    }

    @Test
    fun `nothing is checked while psiphon is starting`() {
        val rig = Rig()
        rig.relay.isStarting = true
        rig.relay.health = Health.Stopped
        rig.carrying[TUNNEL] = false
        repeat(4) { assertNull(rig.probe()) }
        assertEquals(0, rig.downs)
    }

    @Test
    fun `psiphon's tunnel notices move between up, lost and found again`() {
        assertEquals(Tunnel.Waiting, PsiphonRelay.nextTunnel(Tunnel.Waiting, 0))
        assertEquals(Tunnel.Up, PsiphonRelay.nextTunnel(Tunnel.Waiting, 1))
        assertEquals(Tunnel.Lost, PsiphonRelay.nextTunnel(Tunnel.Up, 0))
        assertEquals(Tunnel.Up, PsiphonRelay.nextTunnel(Tunnel.Lost, 2))
    }

    @Test
    fun `the probe counts a proxy only once it grants a connect`() {
        assertTrue(SocksProbe.carries(fakeProxy(grants = true), timeoutMs = 2_000))
        assertFalse(SocksProbe.carries(fakeProxy(grants = false), timeoutMs = 2_000))
    }

    /**
     * A SOCKS5 proxy that grants every CONNECT, or one that holds its port and
     * closes every connection, the way the core does before its tunnel is up.
     */
    private fun fakeProxy(grants: Boolean): Int {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.use {
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    client.use {
                        if (!grants) return@use
                        val input = it.getInputStream()
                        val output = it.getOutputStream()
                        input.readNBytes(3)
                        output.write(byteArrayOf(0x05, 0x00))
                        val head = input.readNBytes(5)
                        input.readNBytes(head[4].toInt() + 2)
                        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        output.flush()
                    }
                }
            }
        }
        return server.localPort
    }

    private companion object {
        const val TUNNEL = 40001
        const val RELAY = 40011
    }
}
