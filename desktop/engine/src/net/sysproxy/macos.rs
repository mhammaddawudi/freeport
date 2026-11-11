//! macOS: points the system at Freeport through `networksetup`, and puts back whatever
//! was there before.
//!
//! Safari, Chrome and Electron apps such as Claude's desktop app follow the
//! system proxy. The settings found before Freeport took over are saved to a
//! file, so a crashed session can still be undone with `freeport proxy off`.

use std::fs;
use std::path::PathBuf;
use std::process::Command;

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

use crate::settings;

const TOOL: &str = "/usr/sbin/networksetup";

/// The three proxy slots macOS keeps per network service.
const KINDS: [Kind; 3] = [Kind::Web, Kind::SecureWeb, Kind::Socks];

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
enum Kind {
    Web,
    SecureWeb,
    Socks,
}

impl Kind {
    fn noun(self) -> &'static str {
        match self {
            Kind::Web => "webproxy",
            Kind::SecureWeb => "securewebproxy",
            Kind::Socks => "socksfirewallproxy",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Slot {
    service: String,
    kind: Kind,
    enabled: bool,
    server: String,
    port: String,
}

fn saved_file() -> Result<PathBuf> {
    Ok(settings::data_dir()?.join("system-proxy-before.json"))
}

fn run(args: &[&str]) -> Result<String> {
    let output = Command::new(TOOL).args(args).output().with_context(|| format!("could not run {TOOL}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        bail!("networksetup {} failed: {}", args.first().unwrap_or(&""), stderr.trim());
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Enabled network services: Wi-Fi, Ethernet, Thunderbolt Bridge and so on.
fn services() -> Result<Vec<String>> {
    Ok(parse_services(&run(&["-listallnetworkservices"])?))
}

fn parse_services(listing: &str) -> Vec<String> {
    listing
        .lines()
        .skip(1) // "An asterisk (*) denotes that a network service is disabled."
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('*'))
        .map(str::to_string)
        .collect()
}

fn read_slot(service: &str, kind: Kind) -> Result<Slot> {
    let listing = run(&[&format!("-get{}", kind.noun()), service])?;
    Ok(parse_slot(service, kind, &listing))
}

fn parse_slot(service: &str, kind: Kind, listing: &str) -> Slot {
    let field = |name: &str| {
        listing
            .lines()
            .find_map(|line| line.strip_prefix(name).and_then(|rest| rest.strip_prefix(':')))
            .map(|value| value.trim().to_string())
            .unwrap_or_default()
    };
    Slot {
        service: service.to_string(),
        kind,
        enabled: field("Enabled").eq_ignore_ascii_case("yes"),
        server: field("Server"),
        port: field("Port"),
    }
}

pub fn enable(socks_port: u16, http_port: u16) -> Result<()> {
    let services = services()?;
    // Only the first takeover is recorded: a second one would save Freeport's
    // own settings as "what was there before".
    if !left_on() {
        let mut before = Vec::new();
        for service in &services {
            for kind in KINDS {
                before.push(read_slot(service, kind)?);
            }
        }
        let text = serde_json::to_string_pretty(&before)?;
        fs::write(saved_file()?, text).context("could not save the current system proxy")?;
    }
    for service in &services {
        for kind in KINDS {
            let port = if kind == Kind::Socks { socks_port } else { http_port };
            run(&[&format!("-set{}", kind.noun()), service, "127.0.0.1", &port.to_string()])?;
        }
    }
    log::info!(target: "freeport", "[+] system proxy now points at Freeport");
    Ok(())
}

/// Puts back the proxies found before [`enable`], or turns them off when
/// nothing was recorded.
pub fn disable() -> Result<()> {
    let saved: Vec<Slot> = saved_file()
        .ok()
        .and_then(|path| fs::read_to_string(path).ok())
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default();
    for service in services()? {
        for kind in KINDS {
            let before = saved.iter().find(|slot| slot.service == service && slot.kind == kind);
            restore(&service, kind, before)?;
        }
    }
    let _ = fs::remove_file(saved_file()?);
    let note = if saved.iter().any(|slot| slot.enabled) { "restored to what it was" } else { "turned off" };
    log::info!(target: "freeport", "[*] system proxy {note}");
    Ok(())
}

fn restore(service: &str, kind: Kind, before: Option<&Slot>) -> Result<()> {
    if let Some(slot) = before.filter(|slot| !slot.server.is_empty() && slot.port != "0") {
        run(&[&format!("-set{}", kind.noun()), service, &slot.server, &slot.port])?;
        if slot.enabled {
            return Ok(());
        }
    }
    run(&[&format!("-set{}state", kind.noun()), service, "off"])?;
    Ok(())
}

/// True while Freeport has the system proxy, including after a crash.
pub fn left_on() -> bool {
    saved_file().map(|path| path.exists()).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_services_and_the_header_are_skipped() {
        let listing = "An asterisk (*) denotes that a network service is disabled.\nWi-Fi\n*Bluetooth PAN\nThunderbolt Bridge\n";
        assert_eq!(parse_services(listing), vec!["Wi-Fi", "Thunderbolt Bridge"]);
    }

    #[test]
    fn a_slot_is_read_from_networksetup_output() {
        let listing = "Enabled: Yes\nServer: 127.0.0.1\nPort: 10808\nAuthenticated Proxy Enabled: 0\n";
        let slot = parse_slot("Wi-Fi", Kind::Web, listing);
        assert!(slot.enabled);
        assert_eq!((slot.server.as_str(), slot.port.as_str()), ("127.0.0.1", "10808"));

        let off = parse_slot("Wi-Fi", Kind::Socks, "Enabled: No\nServer: \nPort: 0\n");
        assert!(!off.enabled);
    }
}
