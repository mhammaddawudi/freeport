package io.github.mhammaddawudi.freeport.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardConfigTest {

    private val privateKey = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
    private val peerKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

    /** The same layout `config.rs` writes with `toml::to_string_pretty`. */
    private val identityToml = """
        device_id = "t.0123456789abcdef"
        access_token = "secret"
        cert_pem = ""
        key_pem = ""
        cert_issued_at = 0
        ipv4 = "172.16.0.2"
        ipv6 = "2606:4700:110:8a3c::1"
        wg_private_key = "$privateKey"
        wg_peer_public_key = "$peerKey"
        client_id = "AAAA"
        organization = ""
        gateway_proxy = ""
        assigned_endpoint = ""
        warp_enabled = true
    """.trimIndent()

    @Test
    fun readsBothKeysFromTheIdentityFile() {
        val keys = WireGuardConfig.keysFromIdentityToml(identityToml)!!
        assertEquals(privateKey, keys.privateKey)
        assertEquals(peerKey, keys.peerPublicKey)
    }

    @Test
    fun rejectsAFileWithoutUsableKeys() {
        assertNull(WireGuardConfig.keysFromIdentityToml("device_id = \"x\"\nwg_private_key = \"short\"\n"))
    }

    @Test
    fun buildsAValidWgQuickConfig() {
        val text = WireGuardConfig.build(privateKey, "172.16.0.2", "2606:4700:110:8a3c::1/128", peerKey, "162.159.192.1:2408")
        val sections = parseWgQuick(text)

        assertEquals(listOf("Interface", "Peer"), sections.map { it.first })
        val iface = sections[0].second
        val peer = sections[1].second
        assertEquals(privateKey, iface["PrivateKey"])
        assertEquals("172.16.0.2/32, 2606:4700:110:8a3c::1/128", iface["Address"])
        assertEquals("1280", iface["MTU"])
        assertTrue(iface.getValue("DNS").split(",").first().trim() == "1.1.1.1")
        assertEquals(peerKey, peer["PublicKey"])
        assertEquals("0.0.0.0/0, ::/0", peer["AllowedIPs"])
        assertEquals("162.159.192.1:2408", peer["Endpoint"])
        assertEquals("25", peer["PersistentKeepalive"])
        assertTrue(WireGuardConfig.isKey(iface.getValue("PrivateKey")))
        assertTrue(WireGuardConfig.isKey(peer.getValue("PublicKey")))
    }

    @Test
    fun bracketsAnIpv6Endpoint() {
        val endpoint = Endpoint("2606:4700:d0::a29f:c001", 500, 40).socket
        val text = WireGuardConfig.build(privateKey, "172.16.0.2", "", peerKey, endpoint)
        assertEquals("[2606:4700:d0::a29f:c001]:500", parseWgQuick(text)[1].second["Endpoint"])
        assertEquals("172.16.0.2/32", parseWgQuick(text)[0].second["Address"])
    }

    /**
     * A strict reading of the wg-quick format: section headers, `Key = Value`
     * lines, blank lines and nothing else, with only the keys wg-quick and
     * the iOS app accept.
     */
    private fun parseWgQuick(text: String): List<Pair<String, Map<String, String>>> {
        val allowed = mapOf(
            "Interface" to setOf("PrivateKey", "Address", "DNS", "MTU", "ListenPort"),
            "Peer" to setOf("PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive"),
        )
        val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
        text.lines().forEach { line ->
            when {
                line.isBlank() -> Unit
                line.startsWith("[") -> {
                    val name = line.removeSurrounding("[", "]")
                    assertTrue("unknown section $name", name in allowed)
                    sections += name to mutableMapOf()
                }
                else -> {
                    val match = Regex("""^(\w+) = (\S.*)$""").matchEntire(line)
                    assertTrue("malformed line: $line", match != null)
                    val (key, value) = match!!.destructured
                    val section = sections.last()
                    assertTrue("$key not allowed in ${section.first}", key in allowed.getValue(section.first))
                    assertTrue("duplicate $key", section.second.put(key, value) == null)
                }
            }
        }
        return sections
    }
}
