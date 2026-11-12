package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanShareTest {
    @Test
    fun connect_targets_split_into_host_and_port() {
        assertEquals("example.com" to 443, LanShare.splitHostPort("example.com:443", 80))
        assertEquals("example.com" to 80, LanShare.splitHostPort("example.com", 80))
        assertEquals("[2001:db8::1]" to 8443, LanShare.splitHostPort("[2001:db8::1]:8443", 443))
        assertNull(LanShare.splitHostPort("example.com:99999", 80))
    }

    @Test
    fun the_request_head_ends_at_the_blank_line() {
        val head = LanShare.readHead("GET http://a/ HTTP/1.1\r\nHost: a\r\n\r\nbody".byteInputStream())
        assertEquals("GET http://a/ HTTP/1.1\r\nHost: a", head)
        assertNull(LanShare.readHead("GET / HTTP/1.1\r\n".byteInputStream()))
    }
}
