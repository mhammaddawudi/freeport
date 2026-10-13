package io.github.mhammaddawudi.freeport.core

import org.json.JSONArray
import org.json.JSONObject
import io.github.mhammaddawudi.freeport.data.AetherSettings

/**
 * Builds the JSON payloads `ffi.rs` deserialises.
 *
 * Field names here match the `#[derive(Deserialize)]` structs exactly:
 * `OpenPayload`, `ScanPayload` and `TunnelPayload`. Serde is strict about
 * types but tolerant about missing fields — every optional one is `#[serde
 * (default)]` — so anything the user left alone is simply left out and the
 * core's own default applies.
 *
 * These are shared by both core implementations so the mock exercises the
 * same payload construction the native path uses.
 */
internal object CorePayloads {

    fun open(path: String, transport: String): String = JSONObject()
        .put("path", path)
        .put("transport", transport)
        .toString()

    fun scan(settings: AetherSettings): String {
        val payload = JSONObject()
            .put("transport", settings.protocol.transport)
            .put("mode", settings.scanMode.wire)
            .put("ip", settings.ipVersion.wire)
            .put("profile", settings.effectiveNoize.wire)
            .put("ech", settings.fetchEch && !settings.protocol.isTcp)

        // A pinned peer means the user does not want a scan at all; the core
        // takes the port list as the only candidate set, so narrow it to one.
        val pinned = parsePeer(settings.pinnedPeer)
        if (pinned != null) {
            payload.put("ports", JSONArray().put(pinned.second))
        }
        return payload.toString()
    }

    fun tunnel(endpoint: Endpoint, settings: AetherSettings): String {
        val payload = JSONObject()
            .put("peer", endpoint.socket)
            .put("transport", settings.protocol.transport)
            .put("socks", "127.0.0.1:${settings.socksPort}")
            .put("profile", settings.effectiveNoize.wire)
            .put("keepalive", settings.keepaliveSeconds)
            .put("ech", settings.fetchEch && !settings.protocol.isTcp)

        if (settings.httpProxyEnabled) {
            payload.put("http", "127.0.0.1:${settings.httpProxyPort}")
        }
        return payload.toString()
    }

    /**
     * The environment the core reads for the switches that have no payload
     * field — the HTTP/2 transport, ClientHello fragmenting and quick
     * reconnect all come from `AETHER_*` variables rather than from JSON.
     *
     * Android gives no supported way to set environment variables for the
     * current process, so [NativeAetherCore] passes these through
     * `aether_core_start` as CLI arguments instead. The map is kept here
     * because it documents the correspondence, and because the mock prints it
     * into the log the way the real core would.
     */
    fun environment(settings: AetherSettings): Map<String, String> = buildMap {
        put("AETHER_PROTOCOL", settings.protocol.transport)
        put("AETHER_SOCKS", "127.0.0.1:${settings.socksPort}")
        put("AETHER_SCAN", settings.scanMode.wire)
        put("AETHER_NOIZE", settings.effectiveNoize.wire)
        put("AETHER_IP", settings.ipVersion.wire)
        // Always rescan: see AetherEngine.clearGatewayCache.
        put("AETHER_QUICK_RECONNECT", "0")

        if (settings.protocol.isTcp) {
            put("AETHER_MASQUE_HTTP2", "1")
            if (settings.fragmentEnabled) {
                put("AETHER_MASQUE_H2_FRAGMENT", "1")
                put("AETHER_MASQUE_H2_FRAGMENT_SIZE", settings.fragmentSize.toString())
                put("AETHER_MASQUE_H2_FRAGMENT_DELAY", settings.fragmentDelayMs.toString())
            }
        } else {
            put("AETHER_WG_KEEPALIVE", settings.keepaliveSeconds.toString())
        }

        if (settings.pinnedPeer.isNotBlank()) {
            put("AETHER_PEER", settings.pinnedPeer.trim())
        }
    }

    /**
     * The full argument list for `aether_core_start`, for the path where the
     * app hands the whole connection to the core rather than driving scan and
     * tunnel itself. Order follows `cli.rs`.
     */
    fun coreArguments(settings: AetherSettings, configDir: String): List<String> = buildList {
        addAll(settings.protocol.coreArguments)
        add("--scan"); add(settings.scanMode.wire)
        add("--noize"); add(settings.effectiveNoize.wire)
        add(
            when (settings.ipVersion) {
                io.github.mhammaddawudi.freeport.data.IpVersion.V4 -> "-4"
                io.github.mhammaddawudi.freeport.data.IpVersion.V6 -> "-6"
                io.github.mhammaddawudi.freeport.data.IpVersion.Dual -> "--dual"
            }
        )
        add("--bind"); add("127.0.0.1:${settings.socksPort}")
        if (!settings.protocol.transport.startsWith("masque")) {
            add("--keepalive"); add(settings.keepaliveSeconds.toString())
        }
        add("--config"); add("$configDir/aether.toml")

        add("--no-quick-reconnect")
        if (settings.pinnedPeer.isNotBlank()) {
            add("--peer"); add(settings.pinnedPeer.trim())
        }
        if (settings.protocol.isTcp && settings.fragmentEnabled) {
            add("--fragment")
            add("--fragment-size"); add(settings.fragmentSize.toString())
            add("--fragment-delay"); add(settings.fragmentDelayMs.toString())
        }
    }

    /** Splits `1.2.3.4:443` or `[2606:4700::1]:443` into host and port. */
    fun parsePeer(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val separator = text.lastIndexOf(':')
        if (separator <= 0 || separator == text.length - 1) return null
        val host = text.substring(0, separator).removeSurrounding("[", "]")
        val port = text.substring(separator + 1).toIntOrNull() ?: return null
        if (port !in 1..65535 || host.isEmpty()) return null
        return host to port
    }
}
