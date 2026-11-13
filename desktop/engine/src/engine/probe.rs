//! Pings each WARP protocol while nothing is connected, the way Automatic
//! scans, so the protocol list can show which ones answer on this network.

use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use tokio::time::timeout;
use warp::api::Cancel;

use super::Engine;
use crate::model::Protocol;
use crate::settings::Settings;

/// Shown for a protocol that did not answer.
pub const NO_ANSWER: i64 = -1;
/// A scan may take this long on a slow line; running out keeps the last figure.
const LANE: Duration = Duration::from_secs(30);
/// Cloudflare's published MASQUE edges: a TCP connect to them is H2's quick ping.
const MASQUE_EDGES: [&str; 2] = ["162.159.198.1:443", "162.159.198.2:443"];

impl Engine {
    /// Each protocol's last round trip, and whether a ping is running.
    pub fn reach(&self) -> (Vec<(Protocol, i64)>, bool) {
        let reach = self.reach.lock().iter().map(|(p, ms)| (*p, *ms)).collect();
        (reach, self.probing.load(Ordering::SeqCst))
    }

    pub(crate) fn note_reach(&self, protocol: Protocol, rtt_ms: i64) {
        self.reach.lock().insert(protocol, rtt_ms);
    }

    /// Starts a ping of H2, H3 and WireGuard unless a session or a ping runs.
    pub fn probe(self: &Arc<Self>, base: Settings) {
        if self.watch().borrow().phase.is_engaged() || self.probing.swap(true, Ordering::SeqCst) {
            return;
        }
        let engine = self.clone();
        tokio::spawn(async move {
            let quick = {
                let engine = engine.clone();
                tokio::spawn(async move {
                    if let Some(ms) = quickest_connect().await {
                        if engine.probing.load(Ordering::SeqCst) {
                            engine.note_reach(Protocol::MasqueH2, ms);
                        }
                    }
                })
            };
            let masque = {
                let engine = engine.clone();
                let base = base.clone();
                tokio::spawn(async move {
                    for protocol in [Protocol::MasqueH3, Protocol::MasqueH2] {
                        engine.ping(protocol, &base).await;
                    }
                })
            };
            engine.ping(Protocol::WireGuard, &base).await;
            let _ = masque.await;
            let _ = quick.await;
            engine.probing.store(false, Ordering::SeqCst);
        });
    }

    async fn ping(&self, protocol: Protocol, base: &Settings) {
        // A connect stops the ping: its scans must not race the session's.
        if !self.probing.load(Ordering::SeqCst) {
            return;
        }
        let settings = self.tuned(&base.with_protocol(protocol));
        let rtt = match self.core.identity(protocol).await {
            Ok(identity) => {
                if protocol != Protocol::WireGuard {
                    self.core.apply_environment(&settings);
                }
                match timeout(LANE, self.core.scan(&identity, &settings, &Default::default(), &Cancel::new())).await {
                    Ok(Ok(endpoint)) => Some(endpoint.rtt_ms as i64),
                    Ok(Err(_)) => Some(NO_ANSWER),
                    // Out of time is not a refusal: the last figure stands.
                    Err(_) => None,
                }
            }
            Err(_) => Some(NO_ANSWER),
        };
        if !self.probing.load(Ordering::SeqCst) {
            return;
        }
        match rtt {
            Some(ms) if ms > 0 => self.note_reach(protocol, ms),
            Some(_) if !self.reach.lock().get(&protocol).is_some_and(|ms| *ms > 0) => self.note_reach(protocol, NO_ANSWER),
            _ => {}
        }
    }
}

/// The quickest TCP connect to a MASQUE edge, all at once; about one round trip.
async fn quickest_connect() -> Option<i64> {
    let knocks = MASQUE_EDGES.iter().map(|edge| async move {
        let started = std::time::Instant::now();
        match timeout(Duration::from_secs(2), tokio::net::TcpStream::connect(*edge)).await {
            Ok(Ok(_)) => Some(started.elapsed().as_millis() as i64),
            _ => None,
        }
    });
    futures::future::join_all(knocks).await.into_iter().flatten().min()
}
