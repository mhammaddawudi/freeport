package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.ScanMode
import io.github.mhammaddawudi.freeport.data.WarpSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSettingsTest {
    @Test
    fun onlyTheScanIsForcedAndTheRestStaysAsChosen() {
        val chosen = WarpSettings(
            scanMode = ScanMode.Ironclad,
            noize = NoizeProfile.Gfw,
            autoTune = false,
            fragmentEnabled = true,
            pinnedPeer = "1.2.3.4:5",
        ).automatic()
        assertEquals(ScanMode.Smart, chosen.scanMode)
        assertEquals(NoizeProfile.Gfw, chosen.noize)
        assertFalse(chosen.autoTune)
        assertTrue(chosen.fragmentEnabled)
        assertEquals("1.2.3.4:5", chosen.pinnedPeer)
    }

    @Test
    fun freshSettingsStartAutomatic() {
        assertTrue(WarpSettings().autoTune)
    }
}
