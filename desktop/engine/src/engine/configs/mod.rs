//! The public V2Ray configs shared on GitHub, as one protocol: the pool of
//! those that work here ([`pool::Pool`]), and the fastest of them carried by
//! Xray on the session's SOCKS port. A config that stops working is set
//! aside and the next one takes over on the reconnect.
//!
//! These servers belong to strangers: what crosses them unencrypted can be
//! read there. HTTPS, which is nearly everything, stays private.

mod feed;
mod link;
mod pool;
mod tester;
mod xray;

use std::time::{Duration, Instant};

use anyhow::{bail, Result};
use parking_lot::Mutex;

use self::link::ShareLink;
use crate::net::ports;

const MAX_TRIES: usize = 6;
const TRY_RESERVE: Duration = Duration::from_secs(20);
const QUICK_DROP: Duration = Duration::from_secs(5 * 60);
const BACKGROUND_BUDGET: Duration = Duration::from_secs(300);
/// A deep sieve downloads a few megabytes per config: at most this often.
const DEEP_EVERY: Duration = Duration::from_secs(6 * 3600);
/// Helper ports (fetching through a known config) are searched for from here.
const HELPER_BASE: u16 = 44_819;

pub fn is_available() -> bool {
    xray::is_available()
}

pub struct Configs {
    pool: pool::Pool,
    running: Mutex<Option<xray::Running>>,
    current: Mutex<Option<(ShareLink, Instant)>>,
    deep_at: Mutex<Option<Instant>>,
    /// One sieve at a time: a background one and a connect must not race.
    sieving: tokio::sync::Mutex<()>,
}

impl Default for Configs {
    fn default() -> Configs {
        Configs {
            pool: if cfg!(test) { pool::Pool::default() } else { pool::Pool::load() },
            running: Mutex::new(None),
            current: Mutex::new(None),
            deep_at: Mutex::new(None),
            sieving: tokio::sync::Mutex::new(()),
        }
    }
}

impl Configs {
    /// Whether tested configs are at hand, so a connect takes seconds, not minutes.
    pub fn is_ready(&self) -> bool {
        !self.pool.is_stale()
    }

    /// The quickest pooled config's round trip, shown before any new test.
    pub fn last_rtt_ms(&self) -> Option<u64> {
        self.pool.best_delay()
    }

    /// Whether building the pool needs another way to GitHub: nothing kept,
    /// and no config known to fetch through.
    pub fn needs_bootstrap(&self) -> bool {
        self.pool.is_stale() && self.pool.candidates().is_empty() && !feed::has_copy()
    }

    /// Serves the fastest working config on `port`, building the pool first
    /// when it is thin; `via` are local SOCKS ports that may reach GitHub.
    pub async fn start(&self, port: u16, within: Duration, via: &[u16]) -> Result<()> {
        self.stop().await;
        if !is_available() {
            bail!("xray is not in this build");
        }
        let started = Instant::now();
        // A config that dropped soon after it was chosen is not tried first again.
        if let Some((link, at)) = self.current.lock().take() {
            if at.elapsed() < QUICK_DROP {
                self.pool.mark_failed(&link);
            }
        }
        if self.pool.is_stale() {
            let _sieve = self.sieving.lock().await;
            if self.pool.is_stale() {
                log::info!(target: "freeport", "[*] configs: building the pool");
                let helper = self.helper_route().await;
                let via: Vec<u16> = helper.as_ref().map(|(port, _)| *port).into_iter().chain(via.iter().copied()).collect();
                self.pool.refresh(within.saturating_sub(TRY_RESERVE), &via, pool::QUICK).await;
            }
        }
        for (link, _) in self.pool.candidates().into_iter().take(MAX_TRIES) {
            if started.elapsed() > within {
                break;
            }
            if self.try_link(&link, port).await {
                *self.current.lock() = Some((link, Instant::now()));
                return Ok(());
            }
            self.pool.mark_failed(&link);
        }
        bail!("no working V2Ray config on this network")
    }

    /// A config that worked before, served on a spare port, to fetch the lists through.
    async fn helper_route(&self) -> Option<(u16, xray::Running)> {
        let (link, _) = self.pool.candidates().into_iter().next()?;
        let port = ports::free_from(HELPER_BASE)?;
        let running = xray::start(&[xray::Route { link: link.clone(), port }], "fetch", false).await.ok()?;
        log::info!(target: "freeport", "[*] configs: fetching the lists through {}", link.label());
        Some((port, running))
    }

    async fn try_link(&self, link: &ShareLink, port: u16) -> bool {
        let running = match xray::start(&[xray::Route { link: link.clone(), port }], "session", true).await {
            Ok(running) => running,
            Err(e) => {
                log::warn!(target: "freeport", "[-] configs: xray refused {}: {e:#}", link.label());
                return false;
            }
        };
        let Some(delay) = tester::answers(port).await else {
            log::warn!(target: "freeport", "[-] configs: {} did not answer", link.label());
            return false;
        };
        *self.running.lock() = Some(running);
        log::info!(target: "freeport", "[+] configs: carrying through {}, {delay}ms", link.label());
        self.pool.mark_good(link, delay);
        self.pool.share(Some(port)).await;
        true
    }

    /// While another session is up: refreshes the lists through it and,
    /// when the pool is old or holds few fast configs, sieves again in the
    /// background, so the fastest configs are ready when V2Ray is chosen.
    pub async fn warm(&self, port: u16) {
        if !is_available() || self.running.lock().is_some() {
            return;
        }
        feed::refresh_through(port).await;
        self.pool.share(Some(port)).await;
        let due = self.deep_at.lock().is_none_or(|at| at.elapsed() > DEEP_EVERY);
        if self.pool.is_stale() || (due && self.pool.is_shallow()) {
            let Ok(_sieve) = self.sieving.try_lock() else { return };
            *self.deep_at.lock() = Some(Instant::now());
            log::info!(target: "freeport", "[*] configs: a deep sieve of the public configs in the background");
            self.pool.refresh(BACKGROUND_BUDGET, &[], pool::DEEP).await;
        }
    }

    pub fn is_alive(&self) -> bool {
        self.running.lock().as_mut().is_some_and(xray::Running::is_alive)
    }

    pub async fn stop(&self) {
        self.running.lock().take();
    }
}

#[cfg(test)]
mod live {
    use super::*;

    /// Fetches through FEED_SOCKS, sieves, then carries a session. Run by hand:
    /// FEED_SOCKS=1819 cargo test -p freeport-engine live_v2ray -- --nocapture
    #[tokio::test(flavor = "multi_thread")]
    async fn live_v2ray() {
        let Some(feed_port) = std::env::var("FEED_SOCKS").ok().and_then(|p| p.parse::<u16>().ok()) else { return };
        let pool = pool::Pool::default();
        let found = pool.refresh(Duration::from_secs(120), &[feed_port], pool::QUICK).await;
        println!("pooled {found}");
        assert!(found > 0, "no working config");
        let (link, _) = pool.candidates().into_iter().next().unwrap();
        let port = ports::free_from(45_819).unwrap();
        let _running = xray::start(&[xray::Route { link: link.clone(), port }], "live", true).await.unwrap();
        let delay = tester::answers(port).await;
        println!("session through {} answered in {delay:?}ms", link.label());
        assert!(delay.is_some());
    }
}
