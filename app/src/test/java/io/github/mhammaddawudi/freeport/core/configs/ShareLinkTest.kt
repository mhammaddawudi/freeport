package io.github.mhammaddawudi.freeport.core.configs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ShareLinkTest {
    private val uuid = "3c152dce-588e-4a4c-9c2a-6b1f2b3d4e5f"
    private val key = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"

    @Test
    fun vless_reality_becomes_an_xray_outbound() {
        val link = ShareLink.parse("vless://$uuid@1.2.3.4:443?type=tcp&security=reality&sni=www.example.com&pbk=$key&sid=ab12&fp=chrome&flow=xtls-rprx-vision#name")
        assertNotNull(link)
        val out = link!!.outbound("o")
        assertEquals("vless", out.getString("protocol"))
        val stream = out.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
        assertEquals(key, stream.getJSONObject("realitySettings").getString("publicKey"))
        val user = out.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
        assertEquals("xtls-rprx-vision", user.getString("flow"))
    }

    @Test
    fun ws_tls_and_xhttp_carry_their_paths() {
        val ws = ShareLink.parse("vless://$uuid@cdn.example.com:443?type=ws&security=tls&path=%2Fws%3Fed%3D2048&host=a.example.com&sni=a.example.com")!!
        assertEquals("/ws?ed=2048", ws.outbound("o").getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"))
        val xhttp = ShareLink.parse("vless://$uuid@1.2.3.4:443?type=xhttp&security=tls&path=%2Fx&mode=packet-up&sni=b.example.com")!!
        assertEquals("packet-up", xhttp.outbound("o").getJSONObject("streamSettings").getJSONObject("xhttpSettings").getString("mode"))
    }

    @Test
    fun vmess_trojan_and_shadowsocks_parse() {
        val vmessJson = """{"v":"2","ps":"x","add":"5.6.7.8","port":"8080","id":"$uuid","aid":"0","net":"ws","type":"none","host":"h.example.com","path":"/p","tls":""}"""
        val vmess = ShareLink.parse("vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray()))!!
        assertEquals(8080, vmess.port)
        assertEquals("ws", vmess.network)
        assertNotNull(ShareLink.parse("trojan://secret@9.9.9.9:443?security=tls&sni=t.example.com#t"))
        val ss = ShareLink.parse("ss://" + Base64.getEncoder().encodeToString("chacha20-ietf-poly1305:pass".toByteArray()) + "@7.7.7.7:8388#s")!!
        assertEquals("chacha20-ietf-poly1305", ss.outbound("o").getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("method"))
    }

    @Test
    fun what_xray_would_refuse_is_dropped() {
        assertNull(ShareLink.parse("vless://not-a-uuid@1.2.3.4:443?type=tcp"))
        assertNull(ShareLink.parse("vless://$uuid@1.2.3.4:443?type=kcp"))
        assertNull(ShareLink.parse("vless://$uuid@1.2.3.4:443?security=reality&pbk=short"))
        assertNull(ShareLink.parse("ss://" + Base64.getEncoder().encodeToString("rc4-md5:pass".toByteArray()) + "@7.7.7.7:8388"))
        assertNull(ShareLink.parse("hysteria2://x@1.2.3.4:443"))
    }

    @Test
    fun a_base64_subscription_is_read_whole() {
        val body = Base64.getEncoder().encodeToString("trojan://a@1.1.1.1:443?security=tls\nvless://$uuid@2.2.2.2:443?type=ws".toByteArray())
        assertEquals(2, ShareLink.parseAll(body).size)
    }
}
