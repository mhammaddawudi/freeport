package io.github.mhammaddawudi.freeport.core.configs

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * One public V2Ray config, read from its share link (`vless://`, `vmess://`,
 * `trojan://`, `ss://`) and turned into an Xray outbound.
 *
 * Only what Xray carries is accepted, and every field Xray would reject is
 * checked here: one bad outbound makes Xray refuse a whole test batch.
 */
data class ShareLink(
    val kind: Kind,
    val host: String,
    val port: Int,
    /** UUID for VLESS and VMess, password for Trojan and Shadowsocks. */
    val secret: String,
    /** Transport and security parameters, by their share-link names. */
    val params: Map<String, String>,
    val raw: String,
) {
    enum class Kind { Vless, Vmess, Trojan, Shadowsocks }

    /** Two links that reach the same server the same way are one config. */
    val key: String
        get() = listOf(kind, host.lowercase(), port, secret, network, security, params["path"], params["sni"], params["serviceName"])
            .joinToString("|")

    val network: String get() = (params["type"] ?: "tcp").let { if (it == "raw") "tcp" else it }
    val security: String get() = params["security"]?.takeIf { it.isNotEmpty() && it != "false" } ?: "none"

    /** The Xray outbound for this config, tagged [tag]. */
    fun outbound(tag: String): JSONObject {
        val settings = when (kind) {
            Kind.Vless -> JSONObject().put(
                "vnext",
                JSONArray().put(
                    server().put(
                        "users",
                        JSONArray().put(
                            JSONObject().put("id", secret).put("encryption", params["encryption"]?.takeIf { it.isNotEmpty() } ?: "none").apply {
                                params["flow"]?.takeIf { it.isNotEmpty() }?.let { put("flow", it) }
                            },
                        ),
                    ),
                ),
            )
            Kind.Vmess -> JSONObject().put(
                "vnext",
                JSONArray().put(
                    server().put(
                        "users",
                        JSONArray().put(
                            JSONObject().put("id", secret)
                                .put("alterId", params["aid"]?.toIntOrNull() ?: 0)
                                .put("security", params["scy"]?.takeIf { it in VMESS_CIPHERS } ?: "auto"),
                        ),
                    ),
                ),
            )
            Kind.Trojan -> JSONObject().put("servers", JSONArray().put(server().put("password", secret)))
            Kind.Shadowsocks -> JSONObject().put(
                "servers",
                JSONArray().put(server().put("method", params.getValue("method")).put("password", secret)),
            )
        }
        return JSONObject()
            .put("tag", tag)
            .put("protocol", kind.wire)
            .put("settings", settings)
            .put("streamSettings", stream())
    }

    private fun server() = JSONObject().put("address", host).put("port", port)

    private fun stream(): JSONObject {
        val stream = JSONObject().put("network", if (network == "tcp") "raw" else network).put("security", security)
        val hostHeader = params["host"]?.takeIf { it.isNotEmpty() }
        val path = params["path"]?.takeIf { it.isNotEmpty() } ?: "/"
        when (network) {
            "ws" -> stream.put("wsSettings", JSONObject().put("path", path).apply { hostHeader?.let { put("host", it) } })
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", path).apply { hostHeader?.let { put("host", it) } })
            "grpc" -> stream.put(
                "grpcSettings",
                JSONObject().put("serviceName", params["serviceName"].orEmpty()).put("multiMode", params["mode"] == "multi"),
            )
            "xhttp" -> stream.put(
                "xhttpSettings",
                JSONObject().put("path", path).put("mode", params["mode"]?.takeIf { it in XHTTP_MODES } ?: "auto").apply {
                    hostHeader?.let { put("host", it) }
                    params["extra"]?.let { extra -> runCatching { JSONObject(extra) }.getOrNull()?.let { put("extra", it) } }
                },
            )
            "tcp" -> if (params["headerType"] == "http") {
                stream.put(
                    "rawSettings",
                    JSONObject().put(
                        "header",
                        JSONObject().put("type", "http").put(
                            "request",
                            JSONObject().put("path", JSONArray().put(path)).apply {
                                hostHeader?.let { put("headers", JSONObject().put("Host", JSONArray(it.split(',')))) }
                            },
                        ),
                    ),
                )
            }
        }
        val serverName = params["sni"]?.takeIf { it.isNotEmpty() } ?: hostHeader?.substringBefore(',') ?: host
        val fingerprint = params["fp"]?.takeIf { it in FINGERPRINTS } ?: "chrome"
        when (security) {
            "tls" -> stream.put(
                "tlsSettings",
                // Xray no longer takes allowInsecure; a config that needs it fails its test instead.
                JSONObject().put("serverName", serverName).put("fingerprint", fingerprint).apply {
                        params["alpn"]?.takeIf { it.isNotEmpty() }?.let { put("alpn", JSONArray(it.split(','))) }
                    },
            )
            "reality" -> stream.put(
                "realitySettings",
                JSONObject().put("serverName", serverName).put("fingerprint", fingerprint)
                    .put("publicKey", params.getValue("pbk"))
                    .put("shortId", params["sid"].orEmpty())
                    .put("spiderX", params["spx"]?.takeIf { it.isNotEmpty() } ?: "/"),
            )
        }
        return stream
    }

    companion object {
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val NETWORKS = setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp")
        private val SECURITIES = setOf("none", "tls", "reality")
        private val XHTTP_MODES = setOf("auto", "packet-up", "stream-up", "stream-one")
        private val VMESS_CIPHERS = setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")
        private val FINGERPRINTS = setOf(
            "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized",
        )
        private val SS_METHODS = setOf(
            "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
            "xchacha20-poly1305", "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        )
        private val REALITY_KEY = Regex("^[A-Za-z0-9_-]{43}$")
        private val SHORT_ID = Regex("^[0-9a-fA-F]{0,16}$")

        /** Every config in a subscription body: plain lines, or the whole of it in base64. */
        fun parseAll(body: String): List<ShareLink> {
            val text = if (body.contains("://")) body else decodeBase64(body.trim().replace("\n", "")) ?: return emptyList()
            return text.lineSequence().mapNotNull { parse(it.trim()) }.toList()
        }

        fun parse(line: String): ShareLink? = runCatching {
            when {
                line.startsWith("vless://") -> standard(line, Kind.Vless)
                line.startsWith("trojan://") -> standard(line, Kind.Trojan)
                line.startsWith("vmess://") -> vmess(line)
                line.startsWith("ss://") -> shadowsocks(line)
                else -> null
            }
        }.getOrNull()?.takeIf { it.isUsable() }

        private fun ShareLink.isUsable(): Boolean {
            if (port !in 1..65535 || host.isBlank() || host.contains(' ')) return false
            if (network !in NETWORKS || security !in SECURITIES) return false
            return when (kind) {
                Kind.Vless, Kind.Vmess -> UUID.matches(secret)
                Kind.Trojan -> secret.isNotEmpty()
                Kind.Shadowsocks -> secret.isNotEmpty() && params["method"] in SS_METHODS
            } && (security != "reality" || (REALITY_KEY.matches(params["pbk"].orEmpty()) && SHORT_ID.matches(params["sid"].orEmpty()))) &&
                (kind != Kind.Vless || params["flow"].isNullOrEmpty() || params["flow"] == "xtls-rprx-vision")
        }

        /** `scheme://secret@host:port?params#name` — VLESS and Trojan. */
        private fun standard(line: String, kind: Kind): ShareLink? {
            val uri = URI(line.substringBefore('#').replace(" ", "%20"))
            val secret = uri.rawUserInfo?.let(::decode) ?: return null
            val host = uri.host?.trim('[', ']') ?: return null
            return ShareLink(kind, host, uri.port, secret, query(uri.rawQuery), line)
        }

        private fun vmess(line: String): ShareLink? {
            val json = JSONObject(decodeBase64(line.removePrefix("vmess://").substringBefore('#')) ?: return null)
            val params = buildMap {
                put("type", json.optString("net", "tcp").ifEmpty { "tcp" })
                put("security", if (json.optString("tls") == "tls") "tls" else "none")
                put("headerType", json.optString("type"))
                listOf("host", "path", "sni", "alpn", "fp", "aid", "scy").forEach { key -> json.optString(key).takeIf { it.isNotEmpty() }?.let { put(key, it) } }
                if (json.optString("net") == "grpc") put("serviceName", json.optString("path"))
            }
            return ShareLink(Kind.Vmess, json.optString("add"), json.optString("port").toIntOrNull() ?: 0, json.optString("id"), params, line)
        }

        /** SIP002 (`ss://base64(method:password)@host:port`) or the older all-base64 form. */
        private fun shadowsocks(line: String): ShareLink? {
            val body = line.removePrefix("ss://").substringBefore('#')
            if (body.contains("plugin=")) return null
            val full = if (body.contains('@')) body else decodeBase64(body.substringBefore('?')) ?: return null
            val credentials = full.substringBeforeLast('@').let { decodeBase64(it) ?: decode(it) }
            val address = full.substringAfterLast('@').substringBefore('?').substringBefore('/')
            val method = credentials.substringBefore(':').lowercase()
            val password = credentials.substringAfter(':', "")
            val host = address.substringBeforeLast(':').trim('[', ']')
            val port = address.substringAfterLast(':').toIntOrNull() ?: return null
            return ShareLink(Kind.Shadowsocks, host, port, password, mapOf("method" to method), line)
        }

        private fun query(raw: String?): Map<String, String> =
            raw.orEmpty().split('&').filter { it.contains('=') }.associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('='))
            }

        private fun decode(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

        internal fun decodeBase64(text: String): String? {
            val clean = text.trim().replace("-", "+").replace("_", "/")
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            return runCatching { String(Base64.getDecoder().decode(padded), Charsets.UTF_8) }.getOrNull()
        }

        private val Kind.wire: String
            get() = when (this) {
                Kind.Vless -> "vless"
                Kind.Vmess -> "vmess"
                Kind.Trojan -> "trojan"
                Kind.Shadowsocks -> "shadowsocks"
            }
    }
}
