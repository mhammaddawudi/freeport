package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.ExitCountries
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.ScanMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedAndExitTest {

    @Test
    fun `the exit country is read from the trace`() {
        val trace = "fl=123\nh=www.cloudflare.com\nip=104.28.214.161\nloc=IR\ncolo=FRA\nwarp=on\n"
        assertEquals("IR", ExitLocator.parse(trace))
    }

    @Test
    fun `a trace without a usable country gives nothing`() {
        assertNull(ExitLocator.parse("ip=1.2.3.4\ncolo=FRA\n"))
        assertNull(ExitLocator.parse("loc=XYZ\n"))
    }

    @Test
    fun `country codes outside the offered list fall back to any`() {
        assertEquals("DE", ExitCountries.normalise("de"))
        assertEquals(ExitCountries.ANY, ExitCountries.normalise("IR"))
        assertEquals(ExitCountries.ANY, ExitCountries.normalise(null))
    }

    @Test
    fun `flags are built from regional indicator letters`() {
        assertEquals("🇩🇪", ExitCountries.flag("DE"))
        assertEquals("🌐", ExitCountries.flag(""))
    }

    @Test
    fun `warp in warp keeps its balanced scan while the others go swift`() {
        val swift = AetherSettings(scanMode = ScanMode.Swift)
        assertEquals("balanced", CorePayloads.scanWire(swift.copy(protocol = Protocol.Gool)))
        assertEquals("swift", CorePayloads.scanWire(swift.copy(protocol = Protocol.MasqueH2)))
        assertEquals("swift", CorePayloads.scanWire(swift.copy(protocol = Protocol.WireGuard)))
        assertEquals("thorough", CorePayloads.scanWire(AetherSettings(protocol = Protocol.Gool, scanMode = ScanMode.Thorough)))
    }

    @Test
    fun `megabits per second from bytes and milliseconds`() {
        assertEquals(8.0, SpeedTester.mbps(1_000_000, 1_000), 0.0001)
        assertEquals(0.0, SpeedTester.mbps(1_000, 0), 0.0)
    }

    @Test
    fun `remembered endpoints keep ipv6 addresses intact`() {
        val encoded = PrefsEndpointCache.encode(Endpoint("2606:4700:102::3", 443, 88), 42)
        assertEquals("2606:4700:102::3|443|88|42", encoded)
        assertEquals("masque-h2.4", PrefsEndpointCache.keyOf(Protocol.MasqueH2, "4"))
    }
}
