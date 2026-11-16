package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.ScanMode
import io.github.mhammaddawudi.freeport.data.TunnelMode
import io.github.mhammaddawudi.freeport.data.WarpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSettingsTest {
    @Test
    fun engineChoicesAreAlwaysAutomatic() {
        val pinned = WarpSettings(
            scanMode = ScanMode.Ironclad,
            noize = NoizeProfile.Gfw,
            autoTune = false,
            fragmentEnabled = true,
            pinnedPeer = "1.2.3.4:5",
            tunnelMode = TunnelMode.SelectedApps,
        ).automatic()
        assertEquals(ScanMode.Smart, pinned.scanMode)
        assertTrue(pinned.autoTune && !pinned.fragmentEnabled && pinned.pinnedPeer.isEmpty())
        assertEquals(TunnelMode.SelectedApps, pinned.tunnelMode)
    }
}
