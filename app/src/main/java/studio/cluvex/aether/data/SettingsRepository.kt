package studio.cluvex.aether.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the user can change, in one immutable snapshot. */
data class AetherSettings(
    val protocol: Protocol = Protocol.Gool,
    val language: AppLanguage = AppLanguage.Kurdish,
    /** Whole device, or only the apps in [tunneledApps]. */
    val tunnelMode: TunnelMode = TunnelMode.WholeDevice,
    /** Package names to route, used only in [TunnelMode.SelectedApps]. */
    val tunneledApps: Set<String> = emptySet(),
    val scanMode: ScanMode = ScanMode.Balanced,
    val noize: NoizeProfile = NoizeProfile.Firewall,
    val ipVersion: IpVersion = IpVersion.V4,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val httpProxyEnabled: Boolean = false,
    val httpProxyPort: Int = DEFAULT_HTTP_PORT,
    val fetchEch: Boolean = true,
    val keepaliveSeconds: Int = 5,
    /** TLS ClientHello fragmenting. HTTP/2 only — the core ignores it on h3. */
    val fragmentEnabled: Boolean = false,
    val fragmentSize: Int = 40,
    val fragmentDelayMs: Int = 20,
    val pinnedPeer: String = "",
    val bypassLan: Boolean = true,
) {
    /** The obfuscation profile, corrected if the protocol changed under it. */
    val effectiveNoize: NoizeProfile
        get() = if (noize in protocol.noizeProfiles) noize else {
            NoizeProfile.fromWire(protocol.defaultNoize, NoizeProfile.Firewall)
        }

    companion object {
        const val DEFAULT_SOCKS_PORT = 1819
        const val DEFAULT_HTTP_PORT = 1820
    }
}

/**
 * Settings live in [SharedPreferences] rather than a datastore: the whole set
 * is a handful of scalars, it is read on the main thread exactly once at
 * startup, and the VpnService needs it synchronously when it is restarted by
 * the system with no Activity around.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AetherSettings> = _settings.asStateFlow()

    fun update(transform: (AetherSettings) -> AetherSettings) {
        val next = transform(_settings.value)
        write(next)
        _settings.value = next
    }

    /** The settings as they are on disk, for a service starting without the UI. */
    fun current(): AetherSettings = _settings.value

    private fun read(): AetherSettings {
        val defaults = AetherSettings()
        val protocol = Protocol.fromId(prefs.getString(KEY_PROTOCOL, null))
        return AetherSettings(
            protocol = protocol,
            language = AppLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null)),
            tunnelMode = TunnelMode.fromWire(prefs.getString(KEY_TUNNEL_MODE, null)),
            // getStringSet hands back a live instance the docs forbid mutating.
            tunneledApps = prefs.getStringSet(KEY_TUNNELED_APPS, null)?.toSet() ?: emptySet(),
            scanMode = ScanMode.fromWire(prefs.getString(KEY_SCAN, null)),
            noize = NoizeProfile.fromWire(
                prefs.getString(KEY_NOIZE, null),
                NoizeProfile.fromWire(protocol.defaultNoize),
            ),
            ipVersion = IpVersion.fromWire(prefs.getString(KEY_IP, null)),
            socksPort = prefs.getInt(KEY_SOCKS_PORT, defaults.socksPort),
            httpProxyEnabled = prefs.getBoolean(KEY_HTTP_ENABLED, defaults.httpProxyEnabled),
            httpProxyPort = prefs.getInt(KEY_HTTP_PORT, defaults.httpProxyPort),
            fetchEch = prefs.getBoolean(KEY_ECH, defaults.fetchEch),
            keepaliveSeconds = prefs.getInt(KEY_KEEPALIVE, defaults.keepaliveSeconds),
            fragmentEnabled = prefs.getBoolean(KEY_FRAGMENT, defaults.fragmentEnabled),
            fragmentSize = prefs.getInt(KEY_FRAGMENT_SIZE, defaults.fragmentSize),
            fragmentDelayMs = prefs.getInt(KEY_FRAGMENT_DELAY, defaults.fragmentDelayMs),
            pinnedPeer = prefs.getString(KEY_PEER, null) ?: defaults.pinnedPeer,
            bypassLan = prefs.getBoolean(KEY_BYPASS_LAN, defaults.bypassLan),
        )
    }

    private fun write(value: AetherSettings) {
        prefs.edit()
            .putString(KEY_PROTOCOL, value.protocol.id)
            .putString(KEY_LANGUAGE, value.language.tag)
            .putString(KEY_TUNNEL_MODE, value.tunnelMode.wire)
            .putStringSet(KEY_TUNNELED_APPS, value.tunneledApps)
            .putString(KEY_SCAN, value.scanMode.wire)
            .putString(KEY_NOIZE, value.effectiveNoize.wire)
            .putString(KEY_IP, value.ipVersion.wire)
            .putInt(KEY_SOCKS_PORT, value.socksPort)
            .putBoolean(KEY_HTTP_ENABLED, value.httpProxyEnabled)
            .putInt(KEY_HTTP_PORT, value.httpProxyPort)
            .putBoolean(KEY_ECH, value.fetchEch)
            .putInt(KEY_KEEPALIVE, value.keepaliveSeconds)
            .putBoolean(KEY_FRAGMENT, value.fragmentEnabled)
            .putInt(KEY_FRAGMENT_SIZE, value.fragmentSize)
            .putInt(KEY_FRAGMENT_DELAY, value.fragmentDelayMs)
            .putString(KEY_PEER, value.pinnedPeer)
            .putBoolean(KEY_BYPASS_LAN, value.bypassLan)
            .apply()
    }

    companion object {
        /**
         * Reads the language before anything else exists — the activity needs
         * it in attachBaseContext, long before the repository is built.
         */
        fun savedLanguage(context: Context): AppLanguage = AppLanguage.fromTag(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null),
        )

        const val FILE = "aether.settings"
        const val KEY_LANGUAGE = "language"
        const val KEY_PROTOCOL = "protocol"
        const val KEY_TUNNEL_MODE = "tunnel_mode"
        const val KEY_TUNNELED_APPS = "tunneled_apps"
        const val KEY_SCAN = "scan_mode"
        const val KEY_NOIZE = "noize"
        const val KEY_IP = "ip_version"
        const val KEY_SOCKS_PORT = "socks_port"
        const val KEY_HTTP_ENABLED = "http_enabled"
        const val KEY_HTTP_PORT = "http_port"
        const val KEY_ECH = "ech"
        const val KEY_KEEPALIVE = "keepalive"
        const val KEY_FRAGMENT = "fragment"
        const val KEY_FRAGMENT_SIZE = "fragment_size"
        const val KEY_FRAGMENT_DELAY = "fragment_delay"
        const val KEY_PEER = "pinned_peer"
        const val KEY_BYPASS_LAN = "bypass_lan"
    }
}
