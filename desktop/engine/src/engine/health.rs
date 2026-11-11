//! Keeps a held session honest. A tunnel or a Psiphon exit can stop carrying
//! traffic while its process looks perfectly alive, so both are checked by
//! opening real connections through them, and region unlock fails closed:
//! while Psiphon is down nothing is carried, rather than leaving from Iran.

use std::sync::Arc;
use std::time::Duration;

use tokio::time::Instant;

use super::relay::Health;
use super::{Engine, Phase};
use crate::net::ports;

/// How often a connection is opened through the tunnel and through Psiphon.
const PROBE_EVERY: Duration = Duration::from_secs(15);
/// After a miss the next probe comes sooner, so a dead hop is found in
/// seconds rather than minutes.
const RETRY_AFTER_MISS: Duration = Duration::from_secs(5);
/// Misses in a row before the tunnel counts as dead: ~45s on a slow line.
const TUNNEL_MISSES: u32 = 3;
/// Misses in a row before Psiphon counts as lost though it says it is up.
const RELAY_MISSES: u32 = 2;
/// How long Psiphon gets to find another server on its own before it is
/// restarted.
const RELAY_REGAIN: Duration = Duration::from_secs(15);
const REGAIN_POLL: Duration = Duration::from_millis(500);
const RELAY_RESTART: Duration = Duration::from_secs(75);

/// The probes of one held session.
pub(super) struct Watch {
    inner: u16,
    relay: Option<u16>,
    next_probe: Instant,
    tunnel_misses: u32,
    relay_misses: u32,
    moved: u64,
}

impl Watch {
    pub fn new(inner: u16, relay: Option<u16>) -> Watch {
        Watch { inner, relay, next_probe: Instant::now() + PROBE_EVERY, tunnel_misses: 0, relay_misses: 0, moved: 0 }
    }
}

impl Engine {
    /// One round of checks. Returns why the session has to end, if it does.
    pub(super) async fn check_health(self: &Arc<Self>, watch: &mut Watch) -> Option<String> {
        if watch.relay.is_some() && self.relay.is_starting() {
            return None;
        }
        if let Some(port) = watch.relay {
            if self.relay.health() != Health::Up {
                return self.recover_unlock(watch.inner, port, true).await;
            }
        }
        if Instant::now() < watch.next_probe {
            return None;
        }
        watch.next_probe = Instant::now() + PROBE_EVERY;

        // Over 64 KB came in since the last probe: the tunnel is busy, and a
        // probe that timed out behind that traffic says nothing about it.
        let moved = self.front.stats.down.load(std::sync::atomic::Ordering::Relaxed);
        let busy = moved.saturating_sub(watch.moved) > 64 * 1024;
        watch.moved = moved;
        if !ports::carries(watch.inner).await {
            if busy {
                watch.tunnel_misses = 0;
                return None;
            }
            watch.tunnel_misses += 1;
            watch.next_probe = Instant::now() + RETRY_AFTER_MISS;
            log::warn!(target: "freeport", "[-] the tunnel did not carry a test connection ({}/{TUNNEL_MISSES})", watch.tunnel_misses);
            if watch.tunnel_misses >= TUNNEL_MISSES {
                return Some("the tunnel stopped carrying traffic".to_string());
            }
            // Psiphon rides the tunnel; probing it now would only blame it.
            return None;
        }
        watch.tunnel_misses = 0;

        let port = watch.relay?;
        if ports::carries(port).await {
            watch.relay_misses = 0;
            return None;
        }
        watch.relay_misses += 1;
        watch.next_probe = Instant::now() + RETRY_AFTER_MISS;
        log::warn!(target: "freeport", "[-] psiphon did not carry a test connection ({}/{RELAY_MISSES})", watch.relay_misses);
        if watch.relay_misses < RELAY_MISSES {
            return None;
        }
        watch.relay_misses = 0;
        // Psiphon still says it is up, so waiting for it to notice is no use.
        self.recover_unlock(watch.inner, port, false).await
    }

    /// Stops carrying and brings Psiphon back. When Psiphon itself reported
    /// the loss (`may_regain`) it gets a moment to find another server first;
    /// otherwise it is restarted at once. Returns a reason only when it cannot
    /// be brought back.
    async fn recover_unlock(self: &Arc<Self>, inner: u16, port: u16, may_regain: bool) -> Option<String> {
        self.front.point_at(0);
        self.set_phase(Phase::Unlocking, None);
        log::warn!(target: "freeport", "[-] region unlock is down; holding traffic until psiphon is back");

        let regain_by = Instant::now() + if may_regain { RELAY_REGAIN } else { Duration::ZERO };
        while Instant::now() < regain_by {
            if self.relay.health() == Health::Up && ports::carries(port).await {
                return self.resume(port);
            }
            if self.relay.health() == Health::Stopped {
                break;
            }
            tokio::time::sleep(REGAIN_POLL).await;
        }

        log::info!(target: "freeport", "[*] restarting psiphon");
        let country = self.relay.country();
        match self.relay.start(inner, port, &country, RELAY_RESTART).await {
            Ok(()) => self.resume(port),
            Err(e) => Some(format!("region unlock could not come back: {e:#}")),
        }
    }

    fn resume(&self, port: u16) -> Option<String> {
        self.front.point_at(port);
        self.set_phase(Phase::Connected, None);
        log::info!(target: "freeport", "[+] region unlock is back");
        None
    }
}
