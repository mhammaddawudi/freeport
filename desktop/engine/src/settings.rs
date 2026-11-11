//! What the person chose, kept in the app data folder (`~/Library/Application Support/Freeport` on macOS, `%APPDATA%\Freeport` on Windows).

use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

use crate::model::{IpVersion, Noize, Protocol, ScanMode};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    pub protocol: Protocol,
    pub scan_mode: ScanMode,
    pub noize: Noize,
    pub ip_version: IpVersion,
    /// The SOCKS5 port apps are pointed at. It stays the same whatever runs
    /// behind it, so a reconnect never breaks a configured app.
    pub socks_port: u16,
    /// The HTTP proxy port, for the system proxy and `HTTPS_PROXY`.
    pub http_port: u16,
    pub region_unlock: bool,
    /// Two-letter country for the region-unlock exit; empty lets Psiphon pick.
    pub exit_country: String,
    /// Point the system proxy at Freeport while connected.
    pub system_proxy: bool,
    pub fetch_ech: bool,
    pub keepalive_secs: u16,
    pub fragment: bool,
    pub pinned_peer: String,
    /// Which one-off upgrades of saved choices have run; 0 in older files.
    #[serde(default)]
    pub revision: u32,
}

/// Bump with each upgrade in [`Settings::upgrade`].
const REVISION: u32 = 1;

impl Default for Settings {
    fn default() -> Self {
        Self {
            protocol: Protocol::Auto,
            scan_mode: ScanMode::Balanced,
            noize: Noize::Firewall,
            ip_version: IpVersion::V4,
            socks_port: 1819,
            http_port: 1820,
            region_unlock: true,
            exit_country: String::new(),
            system_proxy: false,
            fetch_ech: true,
            keepalive_secs: 5,
            fragment: false,
            pinned_peer: String::new(),
            revision: REVISION,
        }
    }
}

impl Settings {
    /// The chosen obfuscation, or the protocol's own when the choice belongs
    /// to the other transport's vocabulary.
    pub fn effective_noize(&self) -> Noize {
        match self.protocol.noize_choices().contains(&self.noize) {
            true => self.noize,
            false => self.protocol.default_noize(),
        }
    }

    pub fn with_protocol(&self, protocol: Protocol) -> Settings {
        Settings { protocol, ..self.clone() }
    }

    pub fn load() -> Settings {
        let Ok(path) = settings_file() else {
            return Settings::default();
        };
        let saved: Option<Settings> = fs::read_to_string(&path).ok().and_then(|text| serde_json::from_str(&text).ok());
        match saved {
            Some(settings) if settings.revision < REVISION => {
                let upgraded = settings.upgrade();
                let _ = upgraded.save();
                upgraded
            }
            Some(settings) => settings,
            None => Settings::default(),
        }
    }

    /// Brings choices saved by an older build up to date.
    fn upgrade(self) -> Settings {
        // 1: Balanced became the scan everyone starts on.
        Settings { scan_mode: ScanMode::Balanced, revision: REVISION, ..self }
    }

    pub fn save(&self) -> Result<()> {
        let path = settings_file()?;
        let text = serde_json::to_string_pretty(self).context("could not encode the settings")?;
        fs::write(&path, text).with_context(|| format!("could not write {}", path.display()))
    }
}

/// The app data folder, created on first use.
pub fn data_dir() -> Result<PathBuf> {
    let base = dirs::data_dir().context("this system has no application support folder")?;
    let dir = base.join("Freeport");
    fs::create_dir_all(&dir).with_context(|| format!("could not create {}", dir.display()))?;
    Ok(dir)
}

pub fn sub_dir(name: &str) -> Result<PathBuf> {
    let dir = data_dir()?.join(name);
    fs::create_dir_all(&dir).with_context(|| format!("could not create {}", dir.display()))?;
    Ok(dir)
}

fn settings_file() -> Result<PathBuf> {
    Ok(data_dir()?.join("settings.json"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noize_from_the_other_vocabulary_falls_back() {
        let settings = Settings { protocol: Protocol::WireGuard, noize: Noize::Gfw, ..Settings::default() };
        assert_eq!(settings.effective_noize(), Noize::Amnezia);
        let masque = settings.with_protocol(Protocol::MasqueH2);
        assert_eq!(masque.effective_noize(), Noize::Gfw);
    }

    #[test]
    fn older_settings_move_to_the_balanced_scan_once() {
        let old: Settings = serde_json::from_str(r#"{"scan_mode":"turbo"}"#).unwrap();
        assert_eq!(old.revision, 0);
        let upgraded = old.upgrade();
        assert_eq!(upgraded.scan_mode, ScanMode::Balanced);
        assert_eq!(upgraded.revision, REVISION);
        let chosen: Settings = serde_json::from_str(r#"{"scan_mode":"turbo","revision":1}"#).unwrap();
        assert_eq!(chosen.scan_mode, ScanMode::Turbo);
    }

    #[test]
    fn missing_fields_take_their_defaults() {
        let settings: Settings = serde_json::from_str(r#"{"protocol":"wire-guard"}"#).unwrap();
        assert_eq!(settings.protocol, Protocol::WireGuard);
        assert_eq!(settings.socks_port, 1819);
        assert!(settings.region_unlock);
    }
}
