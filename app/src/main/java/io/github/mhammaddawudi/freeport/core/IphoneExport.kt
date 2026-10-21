package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.IpVersion
import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.ScanMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** A finished export: the config text and the edge it points at. */
data class IphoneConfig(
    val text: String,
    val endpoint: Endpoint,
    val deviceId: String,
)

/**
 * Makes a plain WireGuard config someone can import on an iPhone.
 *
 * Every export registers a WARP device of its own, in its own identity file,
 * so the identity this phone tunnels with is never handed out. The scan runs
 * here, on this network, with obfuscation off: the iPhone apps that import a
 * wg-quick file speak plain WireGuard, so the edge has to answer plain
 * WireGuard from this provider.
 */
class IphoneExporter(
    private val identityDir: File,
    private val core: AetherCore,
    private val log: CoreLog = CoreLog,
) {

    enum class Phase { Registering, Scanning }

    suspend fun export(
        base: AetherSettings,
        onPhase: (Phase) -> Unit,
    ): CoreResult<IphoneConfig> {
        onPhase(Phase.Registering)
        val file = File(identityDir.apply { mkdirs() }, "export-${System.currentTimeMillis()}.toml")
        log.info("[*] iPhone export: registering a separate device")
        val identity = when (val opened = core.openIdentity(file.absolutePath, Protocol.WireGuard.transport)) {
            is CoreResult.Ok -> opened.value
            is CoreResult.Err -> return opened
        }
        try {
            val keys = withContext(Dispatchers.IO) {
                runCatching { WireGuardConfig.keysFromIdentityToml(file.readText()) }.getOrNull()
            } ?: return CoreResult.Err("the core did not save WireGuard keys for this device")

            onPhase(Phase.Scanning)
            val settings = base.copy(
                protocol = Protocol.WireGuard,
                scanMode = ScanMode.Turbo,
                noize = NoizeProfile.Off,
                // Most iPhones on mobile data have no IPv6 route to an edge.
                ipVersion = IpVersion.V4,
                pinnedPeer = "",
            )
            val endpoint = when (val scanned = core.scan(identity, settings) {}) {
                is CoreResult.Ok -> scanned.value
                is CoreResult.Err -> return scanned
            }
            log.good("[+] iPhone export: ${endpoint.socket} answered in ${endpoint.rttMs}ms")

            val text = WireGuardConfig.build(
                privateKey = keys.privateKey,
                ipv4 = identity.ipv4,
                ipv6 = identity.ipv6,
                peerPublicKey = keys.peerPublicKey,
                endpoint = endpoint.socket,
            )
            return CoreResult.Ok(IphoneConfig(text, endpoint, identity.deviceId))
        } finally {
            withContext(NonCancellable) { core.closeIdentity(identity) }
        }
    }
}

/** Reading the core's identity file and writing a wg-quick config. */
object WireGuardConfig {

    data class Keys(val privateKey: String, val peerPublicKey: String)

    const val DNS = "1.1.1.1, 1.0.0.1"
    const val MTU = 1280
    const val KEEPALIVE = 25

    /**
     * Pulls the two WireGuard keys out of the toml `config.rs` writes for a
     * `PersistedIdentity`. The identity summary the FFI returns leaves them
     * out on purpose, so the saved file is the only place they appear. Both
     * are base64 strings at the top level of the file.
     */
    fun keysFromIdentityToml(toml: String): Keys? {
        fun field(name: String): String? = Regex("""(?m)^\s*$name\s*=\s*["']([^"']*)["']""")
            .find(toml)?.groupValues?.get(1)?.trim()?.takeIf(::isKey)
        val privateKey = field("wg_private_key") ?: return null
        val peerPublicKey = field("wg_peer_public_key") ?: return null
        return Keys(privateKey, peerPublicKey)
    }

    /** A WireGuard key is 32 bytes, so 44 characters of padded base64. */
    fun isKey(value: String): Boolean = Regex("""[A-Za-z0-9+/]{43}=""").matches(value)

    fun build(
        privateKey: String,
        ipv4: String,
        ipv6: String,
        peerPublicKey: String,
        endpoint: String,
        keepalive: Int = KEEPALIVE,
    ): String {
        val addresses = buildList {
            if (ipv4.isNotBlank()) add("${ipv4.substringBefore('/').trim()}/32")
            if (ipv6.isNotBlank()) add("${ipv6.substringBefore('/').trim()}/128")
        }
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = ${addresses.joinToString(", ")}")
            appendLine("DNS = $DNS")
            appendLine("MTU = $MTU")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerPublicKey")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $endpoint")
            appendLine("PersistentKeepalive = $keepalive")
        }
    }
}
