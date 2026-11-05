package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeBoardTest {

    @Test
    fun acceptsOnlyCloudflareEdges() {
        assertTrue(HttpEdgeBoard.isCloudflareEdge("162.159.192.7"))
        assertTrue(HttpEdgeBoard.isCloudflareEdge("188.114.98.1"))
        assertTrue(HttpEdgeBoard.isCloudflareEdge("2606:4700:d0::a29f:c001"))
        assertFalse(HttpEdgeBoard.isCloudflareEdge("188.114.100.1"))
        assertFalse(HttpEdgeBoard.isCloudflareEdge("8.8.8.8"))
        assertFalse(HttpEdgeBoard.isCloudflareEdge("example.com"))
        assertFalse(HttpEdgeBoard.isCloudflareEdge(""))
    }

    @Test
    fun parseDropsForeignAndBrokenEntries() {
        val raw = """{"edges":[
            {"addr":"162.159.198.2","port":443,"rtt":90,"kbps":1800},
            {"addr":"1.2.3.4","port":443,"rtt":10,"kbps":99999},
            {"addr":"162.159.198.3","port":0,"rtt":10,"kbps":500}
        ]}"""
        val edges = HttpEdgeBoard.parse(raw)
        assertEquals(1, edges.size)
        assertEquals(Endpoint("162.159.198.2", 443, 90), edges[0].endpoint)
        assertEquals(1800L, edges[0].kbps)
    }

    @Test
    fun scanPayloadCarriesExcludedEdges() {
        val payload = CorePayloads.scan(
            io.github.mhammaddawudi.freeport.data.WarpSettings(),
            setOf("162.159.198.2:443"),
        )
        assertTrue(payload.contains("\"excluded\":[\"162.159.198.2:443\"]"))
    }

    @Test
    fun aQuickFirstByteBeatsRawSpeedForReels() {
        val fastButFar = SharedEdge(Endpoint("162.159.192.1", 2408, 900), 3000)
        val slowerButNear = SharedEdge(Endpoint("162.159.192.2", 2408, 300), 1200)
        assertTrue(slowerButNear.reelMs < fastButFar.reelMs)
        assertEquals(listOf(slowerButNear, fastButFar), listOf(fastButFar, slowerButNear).sortedBy { it.reelMs })
    }

    @Test
    fun aSlowUploadCostsAnEdgeItsPlace() {
        val choked = SharedEdge(Endpoint("162.159.192.1", 2408, 300), 2000, upKbps = 20)
        val even = SharedEdge(Endpoint("162.159.192.2", 2408, 300), 1500, upKbps = 800)
        val unknown = SharedEdge(Endpoint("162.159.192.3", 2408, 300), 1500)
        assertEquals(listOf(even, unknown, choked), listOf(choked, unknown, even).sortedBy { it.reelMs })
    }

    @Test
    fun parseReadsTheUploadRate() {
        val edges = HttpEdgeBoard.parse("""{"edges":[{"addr":"162.159.198.2","port":443,"rtt":90,"kbps":1800,"up":420}]}""")
        assertEquals(420L, edges.single().upKbps)
    }
}
