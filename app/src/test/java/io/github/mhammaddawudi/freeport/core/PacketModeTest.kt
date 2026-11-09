package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.WarpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketModeTest {

    @Test
    fun `only plain wireguard without the relay goes straight into the tunnel`() {
        assertTrue(CorePayloads.packetMode(WarpSettings(protocol = Protocol.WireGuard)))
        assertFalse(CorePayloads.packetMode(WarpSettings(protocol = Protocol.WireGuard, regionUnlock = true)))
        Protocol.entries.filter { it != Protocol.WireGuard }.forEach {
            assertFalse(CorePayloads.packetMode(WarpSettings(protocol = it)))
        }
    }

    @Test
    fun `wireguard defaults to the amneziawg profile`() {
        val settings = WarpSettings(protocol = Protocol.WireGuard, noize = NoizeProfile.Firewall)
        assertEquals(NoizeProfile.Amnezia, settings.effectiveNoize)
        assertEquals(NoizeProfile.Amnezia, NoizeProfile.fromWire("amnezia"))
    }
}
