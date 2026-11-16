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
/// A ping, not a search: what has not answered by now shows as not answering,
/// unless an earlier figure is known.
const LANE: Duration = Duration::from_secs(8);
/// Cloudflare's published MASQUE edges: a TCP connect to them is H2's quick ping.
pub(crate) const MASQUE_EDGES: [&str; 2] = ["162.159.198.1:443", "162.159.198.2:443"];

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
        if let Some(ms) = self.configs.last_rtt_ms() {
            self.note_reach(Protocol::Configs, ms as i64);
        }
        tokio::spawn(async move {
            // A TCP connect needs no identity: HTTP/2 shows in a second.
            let quick = {
                let engine = engine.clone();
                tokio::spawn(async move {
                    let ms = quickest_connect().await;
                    if let Some(ms) = ms {
                        if engine.probing.load(Ordering::SeqCst) {
                            engine.note_reach(Protocol::MasqueH2, ms);
                        }
                    }
                    ms.is_some()
                })
            };
            let masque = {
                let engine = engine.clone();
                let base = base.clone();
                tokio::spawn(async move {
                    engine.ping(Protocol::MasqueH3, &base).await;
                    // HTTP/2 is scanned only when the knock found nothing.
                    if !quick.await.unwrap_or(false) {
                        engine.ping(Protocol::MasqueH2, &base).await;
                    }
                })
            };
            engine.ping(Protocol::WireGuard, &base).await;
            let _ = masque.await;
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
                    Err(_) => Some(NO_ANSWER),
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

/// The edge of `edges` that accepts a TCP connect soonest, all knocked at once.
pub(crate) async fn quickest_edge(edges: &[std::net::SocketAddr]) -> Option<warp::api::Endpoint> {
    let knocks = edges.iter().map(|edge| async move {
        let started = std::time::Instant::now();
        match timeout(Duration::from_secs(3), tokio::net::TcpStream::connect(*edge)).await {
            Ok(Ok(_)) => Some(warp::api::Endpoint { ip: edge.ip(), port: edge.port(), rtt_ms: started.elapsed().as_millis().max(1) as u64 }),
            _ => None,
        }
    });
    futures::future::join_all(knocks).await.into_iter().flatten().min_by_key(|e| e.rtt_ms)
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
