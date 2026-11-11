//! Background trials while connected, as on the phone: each round tests one
//! edge per protocol over the real line with a probe identity, measures it
//! and shares the result, so the next connection — this Mac's and everyone
//! else's — starts on a measured fast edge. Only runs while the person's
//! traffic is quiet.

use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant};

use warp::api::{Cancel, Endpoint};

use super::board::{self, SharedEdge, SLOW_KBPS};
use super::Engine;
use crate::model::Protocol;
use crate::net::ports;
use crate::settings::Settings;

const FIRST_ROUND: Duration = Duration::from_secs(2 * 60);
const ROUND_EVERY: Duration = Duration::from_secs(10 * 60);
const ROUNDS: usize = 4;
const PROTOCOLS: [Protocol; 3] = [Protocol::MasqueH2, Protocol::MasqueH3, Protocol::WireGuard];

const QUIET_BYTES_PER_SEC: u64 = 40 * 1024;
const QUIET_SAMPLE: Duration = Duration::from_secs(5);
const QUIET_RETRY: Duration = Duration::from_secs(30);
const QUIET_WAIT: Duration = Duration::from_secs(10 * 60);

const TRIAL_BASE: u16 = 43_819;
const TRIAL_STARTUP: Duration = Duration::from_secs(8);
const TRIAL_BYTES: usize = 1_000_000;
const TRIAL_WINDOW: Duration = Duration::from_millis(2_500);
const TRIAL_UP_BYTES: usize = 384 * 1024;

impl Engine {
    pub(super) async fn scout(self: Arc<Self>, session: Settings, current: Endpoint, inner: u16) {
        tokio::time::sleep(FIRST_ROUND).await;
        let mut tried: HashSet<(Protocol, SocketAddr)> = HashSet::from([(session.protocol, current.socket())]);
        for round in 0..ROUNDS {
            let fresh = round % 2 == 1;
            for protocol in PROTOCOLS {
                if !self.await_quiet().await {
                    return;
                }
                let settings = session.with_protocol(protocol);
                // MASQUE reads HTTP/2 or HTTP/3 when a scan or tunnel starts;
                // the person's tunnel has already read it.
                self.core.apply_environment(&settings);
                self.trial(&settings, inner, &mut tried, fresh).await;
                self.core.apply_environment(&session);
            }
            tokio::time::sleep(ROUND_EVERY).await;
        }
    }

    async fn trial(&self, settings: &Settings, inner: u16, tried: &mut HashSet<(Protocol, SocketAddr)>, fresh: bool) {
        let protocol = settings.protocol;
        let Ok(identity) = self.core.probe_identity(protocol).await else { return };
        let skip: HashSet<SocketAddr> = tried
            .iter()
            .filter(|(p, _)| *p == protocol)
            .map(|(_, s)| *s)
            .chain(self.slow.lock().iter().copied())
            .collect();
        let shared = board::fetch(protocol, settings.ip_version, Some(inner)).await;
        let known: HashSet<SocketAddr> = skip.iter().copied().chain(shared.iter().map(|e| e.endpoint().socket())).collect();
        let mut candidate = None;
        if fresh {
            candidate = self.core.scan(&identity, settings, &known, &Cancel::new()).await.ok();
        }
        if candidate.is_none() {
            let mut ranked = shared.clone();
            ranked.sort_by_key(|e| e.reel_ms());
            candidate = ranked.iter().map(|e| e.endpoint()).find(|e| !skip.contains(&e.socket()));
        }
        if candidate.is_none() {
            candidate = self.core.scan(&identity, settings, &skip, &Cancel::new()).await.ok();
        }
        let Some(edge) = candidate.filter(|e| !skip.contains(&e.socket())) else { return };
        tried.insert((protocol, edge.socket()));

        let Some(port) = ports::free_from(TRIAL_BASE) else { return };
        let job = self.core.start_tunnel(identity, edge, settings, port).await;
        let measured = if ports::await_carrying(port, TRIAL_STARTUP).await {
            board::measure(port, TRIAL_BYTES, TRIAL_WINDOW, TRIAL_UP_BYTES).await.ok()
        } else {
            None
        };
        drop(job);
        ports::await_released(port, Duration::from_millis(1_500)).await;

        let Some(m) = measured else {
            log::info!(target: "freeport", "[*] scout: {} {} did not carry anything", protocol.name(), edge.socket());
            return;
        };
        log::info!(
            target: "freeport",
            "[*] scout: {} {} · down {} KB/s · up {} KB/s · first byte {}ms",
            protocol.name(), edge.socket(), m.kbps, m.up, m.first_byte_ms
        );
        let tested = SharedEdge { addr: edge.ip, port: edge.port, rtt: m.first_byte_ms, kbps: m.kbps, up: m.up };
        board::report(protocol, settings.ip_version, tested, Some(inner)).await;
        if m.kbps >= SLOW_KBPS {
            log::info!(target: "freeport", "[+] scout: {} {} added to the pool ({} KB/s)", protocol.name(), edge.socket(), m.kbps);
            self.cache.remember(protocol, settings.ip_version, tested.endpoint());
        }
    }

    /// Waits for a quiet stretch on the front ports; false when none came.
    async fn await_quiet(&self) -> bool {
        let until = Instant::now() + QUIET_WAIT;
        let moved = || self.front.stats.up.load(Ordering::Relaxed) + self.front.stats.down.load(Ordering::Relaxed);
        while Instant::now() < until {
            let before = moved();
            tokio::time::sleep(QUIET_SAMPLE).await;
            let bytes = moved().saturating_sub(before);
            if bytes * 1000 / QUIET_SAMPLE.as_millis() as u64 <= QUIET_BYTES_PER_SEC {
                return true;
            }
            tokio::time::sleep(QUIET_RETRY).await;
        }
        false
    }
}
