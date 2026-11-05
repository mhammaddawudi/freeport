package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreLogTest {

    @Test
    fun noizeProfilesKeepTheAppsName() {
        assertEquals("[+] noize profile: firewall", CoreLog.unbranded("[+] warpnoize profile: firewall"))
        assertEquals("Warp v2.4.0", CoreLog.unbranded("Warp v2.4.0"))
    }
}
