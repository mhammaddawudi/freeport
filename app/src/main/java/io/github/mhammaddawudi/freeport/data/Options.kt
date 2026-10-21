package io.github.mhammaddawudi.freeport.data

import androidx.annotation.StringRes
import io.github.mhammaddawudi.freeport.R

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
 * wireguard mode the CLI drives with `--gool`. [Auto] is resolved by the
 * engine into one of the others before anything reaches the core.
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
    /**
     * Not a transport of its own: the engine pings MASQUE, MASQUE H2 and
     * WireGuard side by side and connects with whichever answers fastest,
     * falling back to warp in warp when none of them does. The default,
     * because which one gets through differs from one network to the next.
     */
    Auto(
        id = "auto",
        displayName = "Automatic",
        transport = "masque",
        coreArguments = emptyList(),
        defaultPort = 443,
        defaultNoize = "firewall",
    ),

    /** Warp in warp: two hops cost latency and buy the most cover. */
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
            Auto, MasqueH3, MasqueH2 -> NoizeProfile.masqueProfiles
            WireGuard, Gool -> NoizeProfile.wireguardProfiles
        }

    companion object {
        fun fromId(id: String?): Protocol = entries.firstOrNull { it.id == id } ?: Auto
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

    /**
     * The default: keeps the fastest edge of those that answer within two
     * seconds of the first one. Close to [Turbo] in time, close to
     * [Balanced] in what it picks.
     */
    Swift("swift", R.string.scan_swift, R.string.scan_swift_desc),
    Balanced("balanced", R.string.scan_balanced, R.string.scan_balanced_desc),
    Thorough("thorough", R.string.scan_thorough, R.string.scan_thorough_desc),
    Stealth("stealth", R.string.scan_stealth, R.string.scan_stealth_desc),
    Ironclad("ironclad", R.string.scan_ironclad, R.string.scan_ironclad_desc);

    companion object {
        fun fromWire(wire: String?): ScanMode = entries.firstOrNull { it.wire == wire } ?: Swift
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
enum class AppLanguage(
    val tag: String,
    @StringRes val labelRes: Int,
    val rightToLeft: Boolean,
) {
    English("en", R.string.language_english, rightToLeft = false),
    Persian("fa", R.string.language_persian, rightToLeft = true),
    Kurdish("ckb", R.string.language_kurdish, rightToLeft = true),
    Kurmanji("kmr", R.string.language_kurmanji, rightToLeft = false);

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: English
    }
}

/**
 * Countries Psiphon can leave from, offered for the region-unlock exit.
 *
 * Kept short on purpose: these are the regions with the most servers, so
 * picking one does not leave the client searching for a minute.
 */
object ExitCountries {
    /** Empty means "any": Psiphon takes whichever server answers first. */
    const val ANY = ""

    val offered = listOf(ANY, "DE", "NL", "GB", "FR", "US", "CA", "SE", "FI", "AT", "SG", "JP")

    fun normalise(code: String?): String {
        val upper = code?.trim()?.uppercase().orEmpty()
        return if (upper in offered) upper else ANY
    }

    /** The flag emoji for a two-letter country code. */
    fun flag(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "\uD83C\uDF10"
        return code.map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
    }
}
