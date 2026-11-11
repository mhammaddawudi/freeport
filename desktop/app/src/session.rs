//! The app's hold on the engine: the saved settings, connect and
//! disconnect, and the system proxy that follows the session.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use anyhow::{anyhow, Result};
use freeport_engine::engine::{Engine, Phase};
use freeport_engine::net::evict;
use freeport_engine::net::front::Front;
use freeport_engine::net::sysproxy;
use freeport_engine::settings::Settings;
use parking_lot::Mutex;
use std::sync::atomic::Ordering::Relaxed;

use crate::view::Traffic;

pub struct Session {
    engine: Option<Arc<Engine>>,
    /// Why the local proxies could not be opened, shown instead of a button.
    pub bind_error: Option<String>,
    settings: Mutex<Settings>,
    /// Freeport set the system proxy for this session, so it puts it back.
    holds_proxy: AtomicBool,
}

impl Session {
    /// Opens the local proxies on the saved ports. A port already taken —
    /// usually another Freeport — leaves the app up, with the reason shown.
    pub async fn open() -> Session {
        let settings = Settings::load();
        // A crash last time can have left the system pointed at a dead proxy.
        if sysproxy::left_on() {
            if let Err(error) = sysproxy::disable() {
                log::warn!(target: "freeport", "[-] could not restore the system proxy: {error:#}");
            }
        }
        let (engine, bind_error) = match bind_front(&settings).await {
            Ok(front) => (Some(Engine::new(front)), None),
            Err(error) => (None, Some(format!("{error:#}"))),
        };
        Session { engine, bind_error, settings: Mutex::new(settings), holds_proxy: AtomicBool::new(false) }
    }

    pub fn engine(&self) -> Result<&Arc<Engine>> {
        self.engine
            .as_ref()
            .ok_or_else(|| anyhow!(self.bind_error.clone().unwrap_or_else(|| "the engine is not running".into())))
    }

    pub fn settings(&self) -> Settings {
        self.settings.lock().clone()
    }

    pub fn save(&self, settings: Settings) -> Result<()> {
        validate(&settings)?;
        settings.save()?;
        *self.settings.lock() = settings;
        Ok(())
    }

    pub async fn connect(&self) -> Result<()> {
        let engine = self.engine()?;
        engine.connect(self.settings()).await;
        Ok(())
    }

    pub async fn disconnect(&self) -> Result<()> {
        let engine = self.engine()?;
        engine.disconnect().await;
        self.release_proxy();
        Ok(())
    }

    pub fn traffic(&self) -> Traffic {
        match &self.engine {
            Some(engine) => Traffic {
                up: engine.front.stats.up.load(Relaxed),
                down: engine.front.stats.down.load(Relaxed),
            },
            None => Traffic { up: 0, down: 0 },
        }
    }

    /// Follows the phase: the system proxy is taken once connected, when the
    /// person asked for it, and given back when the session ends.
    pub fn follow(&self, phase: &Phase) {
        match phase {
            Phase::Connected if self.settings.lock().system_proxy => self.take_proxy(),
            Phase::Idle => self.release_proxy(),
            _ => {}
        }
    }

    fn take_proxy(&self) {
        if self.holds_proxy.swap(true, Ordering::SeqCst) {
            return;
        }
        let settings = self.settings();
        if let Err(error) = sysproxy::enable(settings.socks_port, settings.http_port) {
            self.holds_proxy.store(false, Ordering::SeqCst);
            log::warn!(target: "freeport", "[-] could not set the system proxy: {error:#}");
        }
    }

    pub fn release_proxy(&self) {
        if !self.holds_proxy.swap(false, Ordering::SeqCst) {
            return;
        }
        if let Err(error) = sysproxy::disable() {
            log::warn!(target: "freeport", "[-] could not restore the system proxy: {error:#}");
        }
    }
}

/// Opens the local proxies. When another program — usually another VPN
/// client — holds a port, it is stopped and the port taken over.
async fn bind_front(settings: &Settings) -> Result<Front> {
    match Front::bind(settings.socks_port, settings.http_port).await {
        Ok(front) => Ok(front),
        Err(first) => {
            let mut stopped = evict::evict(settings.socks_port).await;
            stopped.extend(evict::evict(settings.http_port).await);
            if stopped.is_empty() {
                return Err(first);
            }
            let names: Vec<String> = stopped.iter().map(|h| h.name.clone()).collect();
            log::info!(target: "freeport", "[+] took the proxy ports back from {}", names.join(", "));
            Front::bind(settings.socks_port, settings.http_port).await
        }
    }
}

/// Settings from the window are checked before they are saved.
fn validate(settings: &Settings) -> Result<()> {
    if settings.socks_port < 1024 || settings.http_port < 1024 {
        return Err(anyhow!("ports below 1024 need administrator rights"));
    }
    if settings.socks_port == settings.http_port {
        return Err(anyhow!("the SOCKS and HTTP ports must differ"));
    }
    let country = &settings.exit_country;
    if !(country.is_empty() || country.len() == 2 && country.chars().all(|c| c.is_ascii_uppercase())) {
        return Err(anyhow!("'{country}' is not a two-letter country code"));
    }
    if !settings.pinned_peer.is_empty() && settings.pinned_peer.parse::<std::net::SocketAddr>().is_err() {
        return Err(anyhow!("'{}' is not an ip:port", settings.pinned_peer));
    }
    if !(1..=120).contains(&settings.keepalive_secs) {
        return Err(anyhow!("keepalive must be between 1 and 120 seconds"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_defaults_pass() {
        assert!(validate(&Settings::default()).is_ok());
    }

    #[test]
    fn bad_values_are_refused() {
        let base = Settings::default();
        assert!(validate(&Settings { socks_port: 80, ..base.clone() }).is_err());
        assert!(validate(&Settings { http_port: base.socks_port, ..base.clone() }).is_err());
        assert!(validate(&Settings { exit_country: "germany".into(), ..base.clone() }).is_err());
        assert!(validate(&Settings { pinned_peer: "nope".into(), ..base.clone() }).is_err());
        assert!(validate(&Settings { pinned_peer: "162.159.192.1:2408".into(), ..base }).is_ok());
    }
}
