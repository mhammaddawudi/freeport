package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectProtocolTest {

    @Test
    fun `psiphon, tor and the v2ray configs are tunnels of their own, the rest ride cloudflare`() {
        val own = setOf(Protocol.Psiphon, Protocol.Tor, Protocol.Configs)
        own.forEach { assertTrue(it.isDirect) }
        Protocol.entries.filter { it !in own }.forEach { assertFalse(it.isDirect) }
    }

    @Test
    fun `a saved choice of psiphon or tor survives a restart`() {
        assertEquals(Protocol.Psiphon, Protocol.fromId("psiphon"))
        assertEquals(Protocol.Tor, Protocol.fromId("tor"))
    }

    @Test
    fun `neither offers cloudflare's obfuscation profiles`() {
        assertTrue(Protocol.Psiphon.noizeProfiles.isEmpty())
        assertTrue(Protocol.Tor.noizeProfiles.isEmpty())
    }

    @Test
    fun `tor's bridges are snowflake lines`() {
        assertEquals(2, TorTunnel.SNOWFLAKE_BRIDGES.size)
        TorTunnel.SNOWFLAKE_BRIDGES.forEach { line ->
            assertTrue(line.startsWith("snowflake "))
            assertTrue(line.contains("fingerprint=") && line.contains("ice=stun:"))
        }
    }
}
