package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreLogTest {

    @Test
    fun coreLinesDoNotNameAether() {
        assertEquals("[+] noize profile: firewall", CoreLog.unbranded("[+] aethernoize profile: firewall"))
        assertEquals("freeport ends rather than connect", CoreLog.unbranded("Aether ends rather than connect"))
    }
}
