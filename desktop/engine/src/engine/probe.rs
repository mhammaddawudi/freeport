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
            // HTTP/2 shows only once a real handshake answers: Cloudflare's
            // TCP 443 accepts on networks that then drop the tunnel itself.
            let masque = {
                let engine = engine.clone();
                let base = base.clone();
                tokio::spawn(async move {
                    engine.ping(Protocol::MasqueH3, &base).await;
                    engine.ping(Protocol::MasqueH2, &base).await;
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
