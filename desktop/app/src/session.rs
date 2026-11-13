//! The app's hold on the engine: the saved settings, connect and
//! disconnect, and the system proxy that follows the session.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use anyhow::{anyhow, Result};
use freeport_engine::engine::{Engine, Phase};
use freeport_engine::net::evict;
use freeport_engine::net::front::Front;
use freeport_engine::net::lan::{self, LanShare};
use freeport_engine::net::sysproxy;
use freeport_engine::net::tun;
use freeport_engine::settings::Settings;
use parking_lot::Mutex;
use std::sync::atomic::Ordering::Relaxed;

use crate::view::Traffic;

pub struct Session {
    engine: Mutex<Option<Arc<Engine>>>,
    /// Why the local proxies could not be opened, shown instead of a button.
    bind_error: Mutex<Option<String>>,
    settings: Mutex<Settings>,
    /// Freeport set the system proxy for this session, so it puts it back.
    holds_proxy: AtomicBool,
    tun: Arc<tun::Tun>,
    /// The listeners for devices on the same Wi-Fi, while that is on.
    lan: Mutex<Option<LanShare>>,
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
        let session = Session {
            engine: Mutex::new(engine),
            bind_error: Mutex::new(bind_error),
            settings: Mutex::new(settings),
            holds_proxy: AtomicBool::new(false),
            tun: Arc::new(tun::Tun::default()),
            lan: Mutex::new(None),
        };
        session.share_lan().await;
        session
    }

    /// Opens, moves or closes the Wi-Fi listeners to match the settings.
    pub async fn share_lan(&self) {
        let settings = self.settings();
        let front = (settings.socks_port, settings.http_port);
        if !settings.lan_share || self.engine.lock().is_none() {
            *self.lan.lock() = None;
            return;
        }
        if self.lan.lock().as_ref().map(LanShare::front) == Some(front) {
            return;
        }
        *self.lan.lock() = None;
        match LanShare::start(front.0, front.1).await {
            Ok(share) => *self.lan.lock() = Some(share),
            Err(error) => log::warn!(target: "freeport", "[-] Wi-Fi sharing: {error:#}"),
        }
    }

    /// This computer's LAN address while sharing is on.
    pub fn lan_address(&self) -> Option<String> {
        self.lan.lock().as_ref()?;
        lan::address().map(|ip| ip.to_string())
    }

    pub fn engine(&self) -> Result<Arc<Engine>> {
        self.engine
            .lock()
            .clone()
            .ok_or_else(|| anyhow!(self.bind_error().unwrap_or_else(|| "the engine is not running".into())))
    }

    pub fn bind_error(&self) -> Option<String> {
        self.bind_error.lock().clone()
    }

    pub fn settings(&self) -> Settings {
        self.settings.lock().clone()
    }

    /// Saves the settings and moves the proxies at once when their ports
    /// changed. Returns true when this started the engine for the first time
    /// (the saved ports were taken at launch), so the caller can watch it.
    pub async fn save(&self, settings: Settings) -> Result<bool> {
        validate(&settings)?;
        let before = self.settings();
        let moved = (before.socks_port, before.http_port) != (settings.socks_port, settings.http_port);
        let mut started = false;
        if moved {
            let current = self.engine.lock().clone();
            match current {
                Some(engine) => move_front(&engine.front, &settings).await?,
                None => {
                    let front = bind_front(&settings).await?;
                    *self.engine.lock() = Some(Engine::new(front));
                    *self.bind_error.lock() = None;
                    started = true;
                }
            }
        }
        let proxy_was = before.system_proxy;
        let proxy_now = settings.system_proxy;
        settings.save()?;
        *self.settings.lock() = settings;
        if proxy_was && !proxy_now {
            // Switched off: the device's proxy settings are cleared at once.
            self.release_proxy();
            if sysproxy::left_on() {
                if let Err(error) = sysproxy::disable() {
                    log::warn!(target: "freeport", "[-] could not restore the system proxy: {error:#}");
                }
            }
        } else if proxy_now && self.is_connected() {
            // Switched on while connected, or the ports it points at moved.
            if moved {
                self.holds_proxy.store(false, Ordering::SeqCst);
            }
            self.take_proxy();
        }
        self.retune_tun(&before);
        self.share_lan().await;
        Ok(started)
    }

    /// Follows a change of the TUN switches while connected: on, off, or a
    /// different set of apps, which reloads it without asking again.
    fn retune_tun(&self, before: &Settings) {
        let now = self.settings();
        let scope_changed = (before.tun_per_app, &before.proxied_apps, before.socks_port)
            != (now.tun_per_app, &now.proxied_apps, now.socks_port);
        if !now.tun {
            if self.tun.is_running() {
                self.stop_tun();
            }
        } else if self.is_connected() && (!self.tun.is_running() || (scope_changed && now.tun_per_app) || before.socks_port != now.socks_port) {
            self.start_tun();
        }
    }

    fn is_connected(&self) -> bool {
        self.engine.lock().as_ref().is_some_and(|engine| matches!(engine.watch().borrow().phase, Phase::Connected { .. }))
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
        match self.engine.lock().as_ref() {
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
        match phase {
            Phase::Connected if self.settings.lock().tun && !self.tun.is_running() => self.start_tun(),
            Phase::Idle if self.tun.is_running() => self.stop_tun(),
            _ => {}
        }
    }

    /// Starts the interface off the status thread: it waits on a password prompt.
    fn start_tun(&self) {
        let settings = self.settings();
        let scope = if settings.tun_per_app {
            tun::Scope::Apps(settings.proxied_apps.iter().filter_map(|path| crate::apps::program_of(path)).collect())
        } else {
            tun::Scope::Everything
        };
        let tun = self.tun.clone();
        std::thread::spawn(move || {
            if let Err(error) = tun.apply(settings.socks_port, &scope) {
                log::warn!(target: "freeport", "[-] tun: {error:#}");
            }
        });
    }

    pub fn stop_tun(&self) {
        let tun = self.tun.clone();
        std::thread::spawn(move || tun.stop());
    }

    /// Stops the interface before the app exits.
    pub fn stop_tun_now(&self) {
        self.tun.stop();
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

/// Moves the running proxies, taking the new ports from whatever holds them.
async fn move_front(front: &Front, settings: &Settings) -> Result<()> {
    if front.rebind(settings.socks_port, settings.http_port).await.is_ok() {
        return Ok(());
    }
    let (socks, http) = front.ports();
    for port in [settings.socks_port, settings.http_port] {
        if port != socks && port != http {
            evict::evict(port).await;
        }
    }
    front.rebind(settings.socks_port, settings.http_port).await
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
