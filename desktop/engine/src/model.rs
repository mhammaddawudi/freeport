//! The choices Freeport offers, written as the strings the core matches on.
//!
//! `Transport::parse`, `ScanMode::parse` and `noize::from_profile` all fall
//! back to a default on text they do not know, so a renamed wire value would
//! silently change behaviour. Keep them byte-identical to the Android app.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Protocol {
    /// MASQUE H2 first, then the fastest of the others, then warp in warp.
    Auto,
    MasqueH2,
    MasqueH3,
    WireGuard,
    /// Warp in warp, run end to end by the core.
    Gool,
    /// The fastest of the public V2Ray configs, carried by Xray.
    #[serde(rename = "v2ray")]
    Configs,
    /// Psiphon on its own, outside Cloudflare.
    Psiphon,
    /// Tor through Snowflake: the last resort.
    Tor,
}

impl Protocol {
    pub const ALL: [Protocol; 8] = [
        Protocol::Auto,
        Protocol::MasqueH2,
        Protocol::MasqueH3,
        Protocol::WireGuard,
        Protocol::Gool,
        Protocol::Configs,
        Protocol::Psiphon,
        Protocol::Tor,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Protocol::Auto => "Automatic",
            Protocol::MasqueH2 => "MASQUE H2",
            Protocol::MasqueH3 => "MASQUE",
            Protocol::WireGuard => "WireGuard",
            Protocol::Gool => "Warp in Warp",
            Protocol::Configs => "V2Ray",
            Protocol::Psiphon => "Psiphon",
            Protocol::Tor => "Tor",
        }
    }

    pub fn blurb(self) -> &'static str {
        match self {
            Protocol::Auto => "the fastest edge on any protocol",
            Protocol::MasqueH2 => "HTTP/2 over TCP 443, the most reliable here",
            Protocol::MasqueH3 => "HTTP/3 over QUIC",
            Protocol::WireGuard => "classic WARP over UDP",
            Protocol::Gool => "two WireGuard hops, the most cover",
            Protocol::Configs => "public configs, tested here, fastest used",
            Protocol::Psiphon => "outside Cloudflare, no cap",
            Protocol::Tor => "Snowflake, last resort, slow",
        }
    }

    /// The `transport` the core's api takes.
    pub fn transport(self) -> &'static str {
        match self {
            Protocol::WireGuard | Protocol::Gool => "wg",
            _ => "masque",
        }
    }

    /// Psiphon and Tor leave from abroad on their own, without Cloudflare.
    pub fn is_direct(self) -> bool {
        matches!(self, Protocol::Configs | Protocol::Psiphon | Protocol::Tor)
    }

    pub fn is_tcp(self) -> bool {
        self == Protocol::MasqueH2
    }

    pub fn default_noize(self) -> Noize {
        match self {
            Protocol::WireGuard | Protocol::Gool => Noize::Amnezia,
            _ => Noize::Firewall,
        }
    }

    pub fn noize_choices(self) -> &'static [Noize] {
        match self {
            Protocol::WireGuard | Protocol::Gool => &Noize::WIREGUARD,
            _ => &Noize::MASQUE,
        }
    }

    /// The other transports, least like the chosen one first.
    pub fn alternatives(self) -> &'static [Protocol] {
        match self {
            Protocol::Auto => &[],
            Protocol::Gool => &[Protocol::MasqueH3, Protocol::WireGuard, Protocol::MasqueH2],
            Protocol::MasqueH3 => &[Protocol::WireGuard, Protocol::MasqueH2, Protocol::Gool],
            Protocol::MasqueH2 => &[Protocol::MasqueH3, Protocol::WireGuard, Protocol::Gool],
            Protocol::WireGuard => &[Protocol::MasqueH3, Protocol::MasqueH2, Protocol::Gool],
            Protocol::Configs => &[Protocol::MasqueH2, Protocol::MasqueH3, Protocol::WireGuard, Protocol::Psiphon],
            Protocol::Psiphon => &[Protocol::MasqueH2, Protocol::MasqueH3, Protocol::WireGuard, Protocol::Tor],
            Protocol::Tor => &[Protocol::Psiphon, Protocol::MasqueH2],
        }
    }

    pub fn parse(raw: &str) -> Option<Protocol> {
        match raw.trim().to_lowercase().as_str() {
            "auto" | "automatic" => Some(Protocol::Auto),
            "h2" | "masque-h2" => Some(Protocol::MasqueH2),
            "h3" | "masque" | "masque-h3" => Some(Protocol::MasqueH3),
            "wg" | "wireguard" | "warp" => Some(Protocol::WireGuard),
            "gool" | "wiw" | "warp-in-warp" => Some(Protocol::Gool),
            "v2ray" | "configs" => Some(Protocol::Configs),
            "psiphon" => Some(Protocol::Psiphon),
            "tor" | "snowflake" => Some(Protocol::Tor),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ScanMode {
    Swift,
    Turbo,
    Balanced,
    Thorough,
    Stealth,
    Ironclad,
}

impl ScanMode {
    pub const ALL: [ScanMode; 6] = [
        ScanMode::Swift,
        ScanMode::Turbo,
        ScanMode::Balanced,
        ScanMode::Thorough,
        ScanMode::Stealth,
        ScanMode::Ironclad,
    ];

    pub fn wire(self) -> &'static str {
        match self {
            ScanMode::Swift => "swift",
            ScanMode::Turbo => "turbo",
            ScanMode::Balanced => "balanced",
            ScanMode::Thorough => "thorough",
            ScanMode::Stealth => "stealth",
            ScanMode::Ironclad => "ironclad",
        }
    }

    /// Warp in warp keeps the balanced scan: a two second window picked
    /// noticeably slower first hops for it.
    pub fn wire_for(self, protocol: Protocol) -> &'static str {
        match (protocol, self) {
            (Protocol::Gool, ScanMode::Swift) => ScanMode::Balanced.wire(),
            _ => self.wire(),
        }
    }

    pub fn parse(raw: &str) -> Option<ScanMode> {
        ScanMode::ALL.into_iter().find(|mode| mode.wire() == raw.trim().to_lowercase())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Noize {
    Off,
    Light,
    Firewall,
    Gfw,
    Balanced,
    Aggressive,
    /// Junk packets before the handshake, the way the AmneziaWG app sends them.
    Amnezia,
}

impl Noize {
    pub const MASQUE: [Noize; 4] = [Noize::Off, Noize::Light, Noize::Firewall, Noize::Gfw];
    pub const WIREGUARD: [Noize; 5] = [Noize::Amnezia, Noize::Off, Noize::Light, Noize::Balanced, Noize::Aggressive];

    pub fn wire(self) -> &'static str {
        match self {
            Noize::Off => "off",
            Noize::Light => "light",
            Noize::Firewall => "firewall",
            Noize::Gfw => "gfw",
            Noize::Balanced => "balanced",
            Noize::Aggressive => "aggressive",
            Noize::Amnezia => "amnezia",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum IpVersion {
    V4,
    V6,
    Both,
}

impl IpVersion {
    pub fn wire(self) -> &'static str {
        match self {
            IpVersion::V4 => "v4",
            IpVersion::V6 => "v6",
            IpVersion::Both => "both",
        }
    }

    pub fn next(self) -> IpVersion {
        match self {
            IpVersion::V4 => IpVersion::V6,
            IpVersion::V6 => IpVersion::Both,
            IpVersion::Both => IpVersion::V4,
        }
    }
}

/// Exit countries offered for region unlock; empty means Psiphon's choice.
pub const EXIT_COUNTRIES: [&str; 11] = ["", "DE", "NL", "US", "GB", "FR", "CA", "SE", "CH", "JP", "SG"];

/// Countries Anthropic, OpenAI and most licensed services refuse. An exit
/// that lands in one of them is no unlock at all.
pub const REFUSED_COUNTRIES: [&str; 11] = ["IR", "CN", "HK", "MO", "RU", "BY", "KP", "SY", "CU", "VE", "AF"];

/// Where to retry when Psiphon's own pick lands in a refused country.
pub const FALLBACK_EXIT: &str = "DE";

pub fn is_refused(country: &str) -> bool {
    REFUSED_COUNTRIES.contains(&country)
}

pub fn flag(country: &str) -> String {
    let letters: Vec<char> = country.chars().filter(|c| c.is_ascii_alphabetic()).collect();
    if letters.len() != 2 {
        return String::new();
    }
    letters
        .iter()
        .filter_map(|c| char::from_u32(0x1F1E6 + (c.to_ascii_uppercase() as u32 - 'A' as u32)))
        .collect()
}

pub fn cycle<T: Copy + PartialEq>(items: &[T], current: T) -> T {
    let index = items.iter().position(|item| *item == current).unwrap_or(0);
    items[(index + 1) % items.len()]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_strings_match_the_core() {
        assert_eq!(ScanMode::Swift.wire(), "swift");
        assert_eq!(ScanMode::Swift.wire_for(Protocol::Gool), "balanced");
        assert_eq!(Protocol::Gool.transport(), "wg");
        assert_eq!(Protocol::MasqueH2.transport(), "masque");
        assert_eq!(Noize::Gfw.wire(), "gfw");
    }

    #[test]
    fn protocols_parse_from_their_short_names() {
        assert_eq!(Protocol::parse("h2"), Some(Protocol::MasqueH2));
        assert_eq!(Protocol::parse("WIW"), Some(Protocol::Gool));
        assert_eq!(Protocol::parse("nope"), None);
    }

    #[test]
    fn flags_are_regional_indicators() {
        assert_eq!(flag("de"), "🇩🇪");
        assert_eq!(flag(""), "");
    }

    #[test]
    fn cycling_wraps_around() {
        assert_eq!(cycle(&Protocol::ALL, Protocol::Gool), Protocol::Configs);
        assert_eq!(cycle(&Protocol::ALL, Protocol::Tor), Protocol::Auto);
        assert_eq!(cycle(&EXIT_COUNTRIES, "SG"), "");
    }

    #[test]
    fn iran_is_refused_and_germany_is_not() {
        assert!(is_refused("IR"));
        assert!(!is_refused("DE"));
    }
}
