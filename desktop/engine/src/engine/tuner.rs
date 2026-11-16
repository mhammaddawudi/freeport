//! Learns which protocol and obfuscation carry the most here, the way the
//! Android app's Tuner does. Every session's speed is measured once it
//! connects; while nothing has been fast yet, the next connection tries a
//! profile not measured (lighter first, since obfuscation costs speed), and
//! once one is good, or all are measured, the fastest is used.

use std::collections::HashMap;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use parking_lot::Mutex;

use crate::model::{Noize, Protocol};
use crate::settings;

/// Fast enough that looking for better is not worth a slower connection.
pub const GOOD_KBPS: u64 = 1_500;
/// What Automatic assumes of a protocol not measured yet; WireGuard leads.
const UNMEASURED_WARM_KBPS: u64 = 1_000;
const UNMEASURED_KBPS: u64 = 800;
const TTL_SECS: u64 = 7 * 24 * 3600;
const FAILURE_TTL_SECS: u64 = 6 * 3600;
const EXPLORE: [Noize; 2] = [Noize::Off, Noize::Light];
/// A larger MASQUE packet for HTTP/3: less overhead where the path carries it.
pub const WIDE_H3_MTU: u16 = 1350;

/// One combination of per-tunnel settings, measured on its own.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Recipe {
    pub noize: Noize,
    pub masque_mtu: u16,
    /// HTTP/2 only: the TLS ClientHello split across packets.
    pub fragment: bool,
}

impl Recipe {
    pub fn plain(noize: Noize) -> Recipe {
        Recipe { noize, masque_mtu: 0, fragment: false }
    }

    /// The recipe `settings` were tuned to.
    pub fn of(settings: &crate::settings::Settings) -> Recipe {
        Recipe { noize: settings.effective_noize(), masque_mtu: settings.masque_mtu, fragment: settings.fragment }
    }

    fn id(&self) -> String {
        let mut id = self.noize.wire().to_string();
        if self.masque_mtu > 0 {
            id.push_str(&format!("+mtu{}", self.masque_mtu));
        }
        if self.fragment {
            id.push_str("+frag");
        }
        id
    }
}

/// What is worth measuring for `protocol`, the preferred profile first: the
/// obfuscation, and for HTTP/3 the packet size. Only what is read per tunnel.
pub fn recipes(protocol: Protocol, preferred: Noize) -> Vec<Recipe> {
    let choices = protocol.noize_choices();
    let first = if choices.contains(&preferred) { preferred } else { choices[0] };
    let mut noizes = vec![first];
    // Plain WireGuard is fingerprinted where it is filtered at all, so it
    // keeps its disguise; only MASQUE has lighter profiles to try.
    if protocol != Protocol::WireGuard {
        noizes.extend(EXPLORE.iter().copied().filter(|n| choices.contains(n) && *n != first));
    }
    let mut all: Vec<Recipe> = noizes.into_iter().map(Recipe::plain).collect();
    if protocol == Protocol::MasqueH3 {
        all.push(Recipe { noize: first, masque_mtu: WIDE_H3_MTU, fragment: false });
    }
    // A split ClientHello gets HTTP/2 past filters that read the server name.
    if protocol == Protocol::MasqueH2 {
        all.push(Recipe { noize: first, masque_mtu: 0, fragment: true });
    }
    all
}

#[derive(Default)]
pub struct Tuner {
    file: Option<PathBuf>,
    /// "protocol/noize" → (KB/s, when measured, seconds since the epoch).
    known: Mutex<HashMap<String, (u64, u64)>>,
}

impl Tuner {
    /// Kept in the app's data folder, so it survives restarts.
    pub fn load() -> Tuner {
        let file = settings::data_dir().ok().map(|dir| dir.join("tuning.json"));
        let known = file
            .as_ref()
            .and_then(|path| std::fs::read(path).ok())
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Tuner { file, known: Mutex::new(known) }
    }

    /// Folds a measured speed in; zero records a failure, which halves a
    /// speed seen before rather than erasing it.
    pub fn record(&self, protocol: Protocol, recipe: Recipe, kbps: u64) {
        self.record_at(protocol, recipe, kbps, now());
        if let Some(path) = &self.file {
            if let Ok(text) = serde_json::to_vec(&*self.known.lock()) {
                let _ = std::fs::write(path, text);
            }
        }
    }

    fn record_at(&self, protocol: Protocol, recipe: Recipe, kbps: u64, at: u64) {
        let key = key(protocol, recipe);
        let blended = match self.read(&key, at) {
            None | Some(0) => kbps,
            Some(known) if kbps == 0 => known / 2,
            Some(known) => (known + kbps) / 2,
        };
        self.known.lock().insert(key, (blended, at));
    }

    pub fn recipe_for(&self, protocol: Protocol, preferred: Noize) -> Recipe {
        self.recipe_at(protocol, preferred, now())
    }

    fn recipe_at(&self, protocol: Protocol, preferred: Noize, at: u64) -> Recipe {
        let tries = recipes(protocol, preferred);
        let measured: Vec<(Recipe, Option<u64>)> = tries.iter().map(|r| (*r, self.read(&key(protocol, *r), at))).collect();
        let best = measured.iter().filter_map(|(r, k)| k.map(|k| (*r, k))).max_by_key(|(_, k)| *k);
        if let Some((recipe, _)) = best.filter(|(_, k)| *k >= GOOD_KBPS) {
            return recipe;
        }
        // Lighter obfuscation is only worth a look once the protocol is known
        // to get through here; before that it is the likeliest to be blocked.
        let works = best.is_some_and(|(_, k)| k > 0);
        measured
            .iter()
            .filter(|(r, _)| works || r.noize == tries[0].noize)
            .find(|(_, k)| k.is_none())
            .map(|(r, _)| *r)
            .or(best.filter(|_| works).map(|(r, _)| r))
            .unwrap_or(tries[0])
    }

    /// The best speed measured for `protocol`, any profile; None when never measured.
    pub fn measured(&self, protocol: Protocol) -> Option<u64> {
        let at = now();
        protocol.noize_choices().iter().flat_map(|n| recipes(protocol, *n)).filter_map(|r| self.read(&key(protocol, r), at)).max()
    }

    /// What Automatic expects of `protocol`: its measured best, or a prior.
    pub fn expected(&self, protocol: Protocol) -> u64 {
        self.expected_at(protocol, now())
    }

    fn expected_at(&self, protocol: Protocol, at: u64) -> u64 {
        protocol
            .noize_choices()
            .iter()
            .flat_map(|n| recipes(protocol, *n))
            .filter_map(|r| self.read(&key(protocol, r), at))
            .max()
            .unwrap_or(if protocol == Protocol::WireGuard { UNMEASURED_WARM_KBPS } else { UNMEASURED_KBPS })
    }

    fn read(&self, key: &str, at: u64) -> Option<u64> {
        let (kbps, when) = *self.known.lock().get(key)?;
        let ttl = if kbps == 0 { FAILURE_TTL_SECS } else { TTL_SECS };
        (at.saturating_sub(when) < ttl).then_some(kbps)
    }
}

fn key(protocol: Protocol, recipe: Recipe) -> String {
    format!("{}/{}", protocol.name(), recipe.id())
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn plain(noize: Noize) -> Recipe {
        Recipe::plain(noize)
    }

    #[test]
    fn a_slow_masque_profile_makes_way_for_a_lighter_one() {
        let tuner = Tuner::default();
        let p = Protocol::MasqueH3;
        assert_eq!(tuner.recipe_at(p, Noize::Firewall, 10), plain(Noize::Firewall));
        tuner.record_at(p, plain(Noize::Firewall), 300, 10);
        assert_eq!(tuner.recipe_at(p, Noize::Firewall, 10), plain(Noize::Off));
        tuner.record_at(p, plain(Noize::Off), 0, 10);
        assert_eq!(tuner.recipe_at(p, Noize::Firewall, 10), plain(Noize::Light));
        tuner.record_at(p, plain(Noize::Light), 900, 10);
        tuner.record_at(p, Recipe { noize: Noize::Firewall, masque_mtu: WIDE_H3_MTU, fragment: false }, 200, 10);
        assert_eq!(tuner.recipe_at(p, Noize::Firewall, 10), plain(Noize::Light));
        assert_eq!(tuner.expected_at(p, 10), 900);
    }

    #[test]
    fn wireguard_keeps_its_disguise() {
        let tuner = Tuner::default();
        tuner.record_at(Protocol::WireGuard, plain(Noize::Amnezia), 300, 10);
        assert_eq!(tuner.recipe_at(Protocol::WireGuard, Noize::Amnezia, 10), plain(Noize::Amnezia));
    }

    #[test]
    fn a_blocked_default_does_not_try_lighter_profiles() {
        let tuner = Tuner::default();
        tuner.record_at(Protocol::MasqueH2, plain(Noize::Firewall), 0, 10);
        let split = Recipe { noize: Noize::Firewall, masque_mtu: 0, fragment: true };
        assert_eq!(tuner.recipe_at(Protocol::MasqueH2, Noize::Firewall, 10), split);
        tuner.record_at(Protocol::MasqueH2, split, 0, 10);
        assert_eq!(tuner.recipe_at(Protocol::MasqueH2, Noize::Firewall, 10), plain(Noize::Firewall));
    }

    #[test]
    fn http3_also_measures_a_wider_packet() {
        let tuner = Tuner::default();
        for noize in [Noize::Firewall, Noize::Off, Noize::Light] {
            tuner.record_at(Protocol::MasqueH3, plain(noize), 400, 10);
        }
        let wide = Recipe { noize: Noize::Firewall, masque_mtu: WIDE_H3_MTU, fragment: false };
        assert_eq!(tuner.recipe_at(Protocol::MasqueH3, Noize::Firewall, 10), wide);
        tuner.record_at(Protocol::MasqueH3, wide, 1_200, 10);
        assert_eq!(tuner.expected_at(Protocol::MasqueH3, 10), 1_200);
    }

    #[test]
    fn http2_also_measures_a_split_client_hello() {
        let tuner = Tuner::default();
        for noize in [Noize::Firewall, Noize::Off, Noize::Light] {
            tuner.record_at(Protocol::MasqueH2, plain(noize), 400, 10);
        }
        let split = Recipe { noize: Noize::Firewall, masque_mtu: 0, fragment: true };
        assert_eq!(tuner.recipe_at(Protocol::MasqueH2, Noize::Firewall, 10), split);
        assert_eq!(split.id(), "firewall+frag");
    }

    #[test]
    fn a_good_speed_stops_the_search_and_numbers_expire() {
        let tuner = Tuner::default();
        tuner.record_at(Protocol::MasqueH2, plain(Noize::Firewall), 2_400, 10);
        assert_eq!(tuner.recipe_at(Protocol::MasqueH2, Noize::Firewall, 10), plain(Noize::Firewall));
        tuner.record_at(Protocol::MasqueH2, plain(Noize::Firewall), 0, 10);
        assert_eq!(tuner.expected_at(Protocol::MasqueH2, 10), 1_200);
        assert_eq!(tuner.expected_at(Protocol::MasqueH2, 10 + TTL_SECS), UNMEASURED_KBPS);
    }
}
