package studio.cluvex.aether.data

import androidx.annotation.StringRes
import studio.cluvex.aether.R

/**
 * Every value in this file is a string the Rust core already understands.
 *
 * `Transport::parse`, `ScanMode::parse`, `noize::from_profile` and
 * `IpScan::parse` all take free-form text and fall back to a default, so the
 * wire values here are kept byte-identical to the ones those functions match
 * on. Renaming a `wire` string silently changes behaviour to the core default
 * rather than failing, so treat them as a contract.
 */

/**
 * What the app offers on the protocol row.
 *
 * The core's own [`Transport`] enum has only two members — masque and
 * wireguard. The two extra rows here are the transport plus a switch the core
 * reads elsewhere: h2 flips `AETHER_MASQUE_HTTP2`, and gool is the nested
 * wireguard mode the CLI drives with `--gool`.
 */
enum class Protocol(
    val id: String,
    val displayName: String,
    /** The `transport` field of a core payload. */
    val transport: String,
    /** Extra CLI arguments when the core is driven through `aether_core_start`. */
    val coreArguments: List<String>,
    val defaultPort: Int,
    val defaultNoize: String,
) {
    /** Warp in warp. The default: two hops cost latency and buy the most cover. */
    Gool(
        id = "gool",
        displayName = "Warp in Warp",
        transport = "wg",
        coreArguments = listOf("--gool"),
        defaultPort = 2408,
        defaultNoize = "balanced",
    ),
    MasqueH3(
        id = "masque-h3",
        displayName = "MASQUE",
        transport = "masque",
        coreArguments = listOf("--masque"),
        defaultPort = 443,
        defaultNoize = "firewall",
    ),
    MasqueH2(
        id = "masque-h2",
        displayName = "MASQUE H2",
        transport = "masque",
        coreArguments = listOf("--masque", "--h2"),
        defaultPort = 443,
        defaultNoize = "firewall",
    ),
    WireGuard(
        id = "wireguard",
        displayName = "WireGuard",
        transport = "wg",
        coreArguments = listOf("--wg"),
        defaultPort = 2408,
        defaultNoize = "balanced",
    );

    /** True when the transport rides TCP, which is what the fragment options need. */
    val isTcp: Boolean get() = this == MasqueH2

    /** MASQUE and WireGuard take different obfuscation vocabularies. */
    val noizeProfiles: List<NoizeProfile>
        get() = when (this) {
            MasqueH3, MasqueH2 -> NoizeProfile.masqueProfiles
            WireGuard, Gool -> NoizeProfile.wireguardProfiles
        }

    companion object {
        fun fromId(id: String?): Protocol = entries.firstOrNull { it.id == id } ?: Gool
    }
}

/** What the VPN captures. */
enum class TunnelMode(
    val wire: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    /** Everything except this app, which has to reach the edge directly. */
    WholeDevice("all", R.string.routing_whole, R.string.routing_whole_desc),

    /** Only the packages the user ticked. */
    SelectedApps("selected", R.string.routing_selected, R.string.routing_selected_desc);

    companion object {
        fun fromWire(wire: String?): TunnelMode = entries.firstOrNull { it.wire == wire } ?: WholeDevice
    }
}

/**
 * How hard the core looks for a reachable edge before it commits.
 *
 * Mirrors `ScanMode::parse` in `prober.rs`.
 */
enum class ScanMode(
    val wire: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Turbo("turbo", R.string.scan_turbo, R.string.scan_turbo_desc),
    Balanced("balanced", R.string.scan_balanced, R.string.scan_balanced_desc),
    Thorough("thorough", R.string.scan_thorough, R.string.scan_thorough_desc),
    Stealth("stealth", R.string.scan_stealth, R.string.scan_stealth_desc),
    Ironclad("ironclad", R.string.scan_ironclad, R.string.scan_ironclad_desc);

    companion object {
        fun fromWire(wire: String?): ScanMode = entries.firstOrNull { it.wire == wire } ?: Balanced
    }
}

/**
 * The obfuscation profile handed to `noize::from_profile`.
 *
 * The two lists are the same four configs under the names each transport's
 * documentation uses, so the picker reads the way the guide does.
 */
enum class NoizeProfile(
    val wire: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Off("off", R.string.noize_off, R.string.noize_off_desc),
    Light("light", R.string.noize_light, R.string.noize_light_desc),
    Firewall("firewall", R.string.noize_firewall, R.string.noize_firewall_desc),
    Gfw("gfw", R.string.noize_heavy, R.string.noize_heavy_desc),
    Balanced("balanced", R.string.noize_balanced, R.string.noize_balanced_desc),
    Aggressive("aggressive", R.string.noize_aggressive, R.string.noize_aggressive_desc);

    companion object {
        val masqueProfiles = listOf(Off, Light, Firewall, Gfw)
        val wireguardProfiles = listOf(Off, Light, Balanced, Aggressive)

        fun fromWire(wire: String?, fallback: NoizeProfile = Firewall): NoizeProfile =
            entries.firstOrNull { it.wire == wire } ?: fallback
    }
}

/** Mirrors `IpScan::parse`. */
enum class IpVersion(val wire: String, @StringRes val labelRes: Int) {
    V4("4", R.string.ip_v4),
    V6("6", R.string.ip_v6),
    Dual("dual", R.string.ip_dual);

    companion object {
        fun fromWire(wire: String?): IpVersion = entries.firstOrNull { it.wire == wire } ?: V4
    }
}

/** The two languages the interface ships in. */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    Kurdish("ckb", R.string.language_kurdish),
    English("en", R.string.language_english);

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: Kurdish
    }
}
