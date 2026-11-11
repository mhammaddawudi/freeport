//! Windows: the per-user WinINet proxy in the registry, which Edge, Chrome,
//! Electron apps and most others follow. The values found before Freeport
//! took over are saved to a file and written back on [`disable`].

use std::fs;
use std::path::PathBuf;

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_WRITE};
use winreg::RegKey;

use crate::settings;

const INTERNET_SETTINGS: &str = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

/// Local addresses never go through the proxy.
const BYPASS: &str = "localhost;127.*;10.*;172.16.*;192.168.*;<local>";

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Before {
    enabled: u32,
    server: Option<String>,
    bypass: Option<String>,
}

fn saved_file() -> Result<PathBuf> {
    Ok(settings::data_dir()?.join("system-proxy-before.json"))
}

fn key() -> Result<RegKey> {
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(INTERNET_SETTINGS, KEY_READ | KEY_WRITE)
        .context("could not open the Internet Settings key")
}

/// What WinINet expects in ProxyServer: one entry per scheme.
fn server_value(socks_port: u16, http_port: u16) -> String {
    format!("http=127.0.0.1:{http_port};https=127.0.0.1:{http_port};socks=127.0.0.1:{socks_port}")
}

pub fn enable(socks_port: u16, http_port: u16) -> Result<()> {
    let key = key()?;
    if !left_on() {
        let before = Before {
            enabled: key.get_value("ProxyEnable").unwrap_or(0),
            server: key.get_value("ProxyServer").ok(),
            bypass: key.get_value("ProxyOverride").ok(),
        };
        fs::write(saved_file()?, serde_json::to_string_pretty(&before)?).context("could not save the current system proxy")?;
    }
    key.set_value("ProxyServer", &server_value(socks_port, http_port))?;
    key.set_value("ProxyOverride", &BYPASS)?;
    key.set_value("ProxyEnable", &1u32)?;
    announce();
    log::info!(target: "freeport", "[+] system proxy now points at Freeport");
    Ok(())
}

pub fn disable() -> Result<()> {
    let saved: Before = saved_file()
        .ok()
        .and_then(|path| fs::read_to_string(path).ok())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default();
    let key = key()?;
    match &saved.server {
        Some(server) => key.set_value("ProxyServer", server)?,
        None => {
            let _ = key.delete_value("ProxyServer");
        }
    }
    match &saved.bypass {
        Some(bypass) => key.set_value("ProxyOverride", bypass)?,
        None => {
            let _ = key.delete_value("ProxyOverride");
        }
    }
    key.set_value("ProxyEnable", &saved.enabled)?;
    announce();
    let _ = fs::remove_file(saved_file()?);
    let note = if saved.enabled != 0 { "restored to what it was" } else { "turned off" };
    log::info!(target: "freeport", "[*] system proxy {note}");
    Ok(())
}

/// True while Freeport has the system proxy, including after a crash.
pub fn left_on() -> bool {
    saved_file().map(|path| path.exists()).unwrap_or(false)
}

/// Tells running programs the settings changed, so they need no restart.
fn announce() {
    use windows_sys::Win32::Networking::WinInet::{
        InternetSetOptionW, INTERNET_OPTION_REFRESH, INTERNET_OPTION_SETTINGS_CHANGED,
    };
    // SAFETY: both options take no buffer.
    unsafe {
        InternetSetOptionW(std::ptr::null(), INTERNET_OPTION_SETTINGS_CHANGED, std::ptr::null(), 0);
        InternetSetOptionW(std::ptr::null(), INTERNET_OPTION_REFRESH, std::ptr::null(), 0);
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn every_scheme_points_at_freeport() {
        assert_eq!(
            super::server_value(1819, 1820),
            "http=127.0.0.1:1820;https=127.0.0.1:1820;socks=127.0.0.1:1819"
        );
    }
}
