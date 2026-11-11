//! One connection attempt, from scan to the moment the tunnel drops.

use std::sync::Arc;
use std::time::{Duration, Instant};

use warp::account::Identity;
use warp::api::{Cancel, Endpoint};
use tokio::task::JoinHandle;
use tokio::time::timeout;

use super::board::{self, SharedEdge, SLOW_KBPS};
use super::core::CoreJob;
use super::health::Watch;
use super::{Engine, Outcome, Phase, Status};
use crate::model::{self, Protocol};
use crate::net::{exit, ports};
use crate::settings::Settings;

/// The core's own SOCKS port is searched for from here, out of the way of
/// the front ports apps are pointed at.
const INNER_BASE: u16 = 41_819;
/// Psiphon's SOCKS port is searched for from here.
const RELAY_BASE: u16 = 42_819;

const TUNNEL_STARTUP: Duration = Duration::from_secs(75);
/// A good edge on a slow line still takes a few seconds of TCP and TLS.
const REMEMBERED_STARTUP: Duration = Duration::from_secs(15);
const MANAGED_STARTUP: Duration = Duration::from_secs(90);
/// Psiphon's first start downloads its server list; later ones take ~20s.
const RELAY_STARTUP: Duration = Duration::from_secs(75);
const PORT_RELEASE: Duration = Duration::from_millis(1_500);
const POLL: Duration = Duration::from_secs(1);
const LATENCY_EVERY: Duration = Duration::from_secs(15);
const LATENCY_KEPT: usize = 60;

/// Long enough for a few rounds of H2 probes on a line with 2s round trips.
const AUTO_LANE: Duration = Duration::from_secs(10);
const AUTO_PING: Duration = Duration::from_secs(20);
const AUTO_GRACE: Duration = Duration::from_millis(2_500);
const MAX_KNOWN_TRIES: usize = 3;
const SHARED_FETCH: Duration = Duration::from_secs(4);

/// Enough to see whether an edge carries video, cheap enough for every connection.
const JUDGE_BYTES: usize = 2_000_000;
const JUDGE_WINDOW: Duration = Duration::from_millis(2_500);
const UPLOAD_BYTES: usize = 384 * 1024;

/// Aborts a helper task when the attempt that started it ends.
struct Helper(JoinHandle<()>);

impl Drop for Helper {
    fn drop(&mut self) {
        self.0.abort();
    }
}

impl Engine {
    pub(super) async fn attempt_once(self: &Arc<Self>, settings: &Settings) -> Outcome {
        let protocol = settings.protocol;
        self.update(|status| {
            *status = Status { phase: Phase::Preparing, protocol: Some(protocol), ..Status::default() }
        });
        if protocol == Protocol::Auto {
            return self.attempt_auto(settings).await;
        }
        self.core.apply_environment(settings);
        log::info!(
            target: "freeport",
            "[*] {} · scan {} · noize {} · ip {}",
            protocol.name(),
            settings.scan_mode.wire_for(protocol),
            settings.effective_noize().wire(),
            settings.ip_version.wire()
        );
        if protocol == Protocol::Gool {
            return self.run_managed(settings).await;
        }

        let identity = match self.core.identity(protocol).await {
            Ok(identity) => identity,
            Err(e) => return Outcome::failed(format!("identity: {e:#}")),
        };

        if settings.pinned_peer.trim().is_empty() {
            let shared = self.shared(protocol, settings).await;
            for (_, edge) in self.known(&[(protocol, shared)], settings).into_iter().take(MAX_KNOWN_TRIES) {
                let outcome = self.try_remembered(identity.clone(), edge.endpoint(), settings, false).await;
                if outcome.connected || outcome.rotate {
                    return outcome;
                }
            }
            self.cache.forget(protocol, settings.ip_version);
        }

        self.set_phase(Phase::Scanning, Some(protocol));
        log::info!(target: "freeport", "[*] scanning for a reachable edge");
        let skip = self.slow.lock().clone();
        let endpoint = match self.core.scan(&identity, settings, &skip, &Cancel::new()).await {
            Ok(endpoint) => endpoint,
            Err(e) => return Outcome::failed(format!("scan: {e:#}")),
        };
        log::info!(target: "freeport", "[+] edge {} answered in {}ms", endpoint.socket(), endpoint.rtt_ms);
        self.run_tunnel(identity, endpoint, settings, TUNNEL_STARTUP, false).await
    }

    /// Prefers MASQUE H2 whenever its edge answers within [`AUTO_H2_LANE`];
    /// only then races MASQUE and WireGuard, and hands over to warp in warp
    /// when nothing answers. WireGuard scans beside H2 the whole time, so a
    /// blocked H2 costs no extra wait. The two MASQUE carriers never scan at
    /// once: the core reads H2 or H3 from one process-wide variable.
    /// Whatever carries the most, whichever protocol that is: first the
    /// edges measured fastest by anyone, then a scan where every protocol
    /// gets the same chance and the quickest answer wins.
    async fn attempt_auto(self: &Arc<Self>, base: &Settings) -> Outcome {
        let candidates = [Protocol::MasqueH2, Protocol::MasqueH3, Protocol::WireGuard];
        let mut identities = Vec::new();
        for protocol in candidates {
            match self.core.identity(protocol).await {
                Ok(identity) => identities.push((protocol, identity)),
                Err(e) => log::warn!(target: "freeport", "[-] {} identity: {e:#}", protocol.name()),
            }
        }
        let identity_of = |p: Protocol| identities.iter().find(|(q, _)| *q == p).map(|(_, i)| i.clone());

        if base.pinned_peer.trim().is_empty() {
            let mut shared = Vec::new();
            for protocol in candidates {
                shared.push((protocol, self.shared(protocol, base).await));
            }
            let known = self.known(&shared, base);
            if !known.is_empty() {
                log::info!(target: "freeport", "[*] automatic: {} edge(s) known to be fast, fastest first", known.len());
            }
            for (protocol, edge) in known.into_iter().take(MAX_KNOWN_TRIES) {
                let Some(identity) = identity_of(protocol) else { continue };
                let chosen = base.with_protocol(protocol);
                self.core.apply_environment(&chosen);
                let outcome = self.try_remembered(identity, edge.endpoint(), &chosen, true).await;
                if outcome.connected || outcome.rotate {
                    return outcome;
                }
            }
        }

        log::info!(target: "freeport", "[*] automatic: scanning every protocol");
        self.set_phase(Phase::Scanning, Some(Protocol::Auto));
        let wg_lane = identity_of(Protocol::WireGuard)
            .map(|identity| self.spawn_scan(identity, base.with_protocol(Protocol::WireGuard)));
        // The two MASQUE carriers read HTTP/2 or HTTP/3 from one process-wide
        // variable, so they take turns while WireGuard scans beside them.
        let masque = identity_of(Protocol::MasqueH2).map(|identity| {
            let engine = self.clone();
            let base = base.clone();
            tokio::spawn(async move {
                let mut found = Vec::new();
                for protocol in [Protocol::MasqueH2, Protocol::MasqueH3] {
                    let settings = base.with_protocol(protocol);
                    engine.core.apply_environment(&settings);
                    let skip = engine.slow.lock().clone();
                    match timeout(AUTO_LANE, engine.core.scan(&identity, &settings, &skip, &Cancel::new())).await {
                        Ok(Ok(endpoint)) => {
                            log::info!(target: "freeport", "[+] {} answered in {}ms", protocol.name(), endpoint.rtt_ms);
                            found.push((protocol, endpoint));
                        }
                        Ok(Err(e)) => log::warn!(target: "freeport", "[-] {}: {e:#}", protocol.name()),
                        Err(_) => log::warn!(target: "freeport", "[-] {}: no edge within {}s", protocol.name(), AUTO_LANE.as_secs()),
                    }
                }
                found
            })
        });
        let found = race([wg_lane, masque]).await;

        let Some(&(protocol, endpoint)) = found.iter().min_by_key(|(_, e)| e.rtt_ms) else {
            return Outcome::failed("automatic: nothing answered on MASQUE or WireGuard");
        };
        log::info!(target: "freeport", "[+] automatic: {} answered first ({}ms)", protocol.name(), endpoint.rtt_ms);
        let chosen = base.with_protocol(protocol);
        self.core.apply_environment(&chosen);
        let Some(identity) = identity_of(protocol) else {
            return Outcome::failed("identity: not open");
        };
        self.run_tunnel(identity, endpoint, &chosen, TUNNEL_STARTUP, true).await
    }

    async fn shared(&self, protocol: Protocol, settings: &Settings) -> Vec<SharedEdge> {
        timeout(SHARED_FETCH, board::fetch(protocol, settings.ip_version, None)).await.unwrap_or_default()
    }

    /// Known edges across protocols, by how soon a reel would start: the
    /// shared fast ones first, then this Mac's own last good edge per protocol.
    fn known(&self, shared: &[(Protocol, Vec<SharedEdge>)], settings: &Settings) -> Vec<(Protocol, SharedEdge)> {
        let slow = self.slow.lock().clone();
        let mut measured: Vec<(Protocol, SharedEdge)> = shared
            .iter()
            .flat_map(|(p, edges)| edges.iter().map(move |e| (*p, *e)))
            .filter(|(_, e)| e.kbps >= SLOW_KBPS)
            .collect();
        measured.sort_by_key(|(_, e)| e.reel_ms());
        for (protocol, _) in shared {
            if let Some(e) = self.cache.recall(*protocol, settings.ip_version) {
                measured.push((*protocol, SharedEdge { addr: e.ip, port: e.port, rtt: e.rtt_ms, kbps: 0, up: 0 }));
            }
        }
        let mut seen = std::collections::HashSet::new();
        measured
            .into_iter()
            .filter(|(p, e)| !slow.contains(&e.endpoint().socket()) && seen.insert((*p, e.endpoint().socket())))
            .collect()
    }

    fn spawn_scan(self: &Arc<Self>, identity: Arc<Identity>, settings: Settings) -> JoinHandle<Vec<(Protocol, Endpoint)>> {
        let engine = self.clone();
        tokio::spawn(async move {
            let skip = engine.slow.lock().clone();
            match engine.core.scan(&identity, &settings, &skip, &Cancel::new()).await {
                Ok(endpoint) => {
                    log::info!(target: "freeport", "[+] {} answered in {}ms", settings.protocol.name(), endpoint.rtt_ms);
                    vec![(settings.protocol, endpoint)]
                }
                Err(e) => {
                    log::warn!(target: "freeport", "[-] {}: {e:#}", settings.protocol.name());
                    Vec::new()
                }
            }
        })
    }

    async fn try_remembered(
        self: &Arc<Self>,
        identity: Arc<Identity>,
        endpoint: Endpoint,
        settings: &Settings,
        via_auto: bool,
    ) -> Outcome {
        log::info!(target: "freeport", "[*] trying the last good {} edge {} first", settings.protocol.name(), endpoint.socket());
        let outcome = self.run_tunnel(identity, endpoint, settings, REMEMBERED_STARTUP, via_auto).await;
        if !outcome.connected {
            log::warn!(target: "freeport", "[-] {} did not come back ({}), scanning instead", endpoint.socket(), outcome.reason);
        }
        outcome
    }

    async fn run_tunnel(
        self: &Arc<Self>,
        identity: Arc<Identity>,
        endpoint: Endpoint,
        settings: &Settings,
        startup: Duration,
        via_auto: bool,
    ) -> Outcome {
        let Some(port) = ports::free_from(INNER_BASE) else {
            return Outcome::failed("no free local port for the tunnel");
        };
        self.update(|status| {
            status.phase = Phase::Connecting;
            status.protocol = Some(settings.protocol);
            status.endpoint = Some(endpoint);
            status.via_auto = via_auto;
        });
        log::info!(target: "freeport", "[*] opening {} tunnel to {}", settings.protocol.name(), endpoint.socket());
        let job = self.core.start_tunnel(identity, endpoint, settings, port).await;
        let outcome = self.serve(job, Some(endpoint), settings, port, startup).await;
        ports::await_released(port, PORT_RELEASE).await;
        outcome
    }

    async fn run_managed(self: &Arc<Self>, settings: &Settings) -> Outcome {
        let Some(port) = ports::free_from(INNER_BASE) else {
            return Outcome::failed("no free local port for the tunnel");
        };
        self.set_phase(Phase::Connecting, Some(Protocol::Gool));
        log::info!(target: "freeport", "[*] handing the double hop to the core");
        let job = match self.core.start_managed(settings, port) {
            Ok(job) => job,
            Err(e) => return Outcome::failed(format!("core: {e:#}")),
        };
        let outcome = self.serve(job, None, settings, port, MANAGED_STARTUP).await;
        ports::await_released(port, PORT_RELEASE).await;
        outcome
    }

    /// Waits for the core's proxy to accept, then holds the session.
    async fn serve(
        self: &Arc<Self>,
        mut job: CoreJob,
        endpoint: Option<Endpoint>,
        settings: &Settings,
        port: u16,
        startup: Duration,
    ) -> Outcome {
        tokio::select! {
            ready = ports::await_carrying(port, startup) => {
                if !ready {
                    return Outcome::failed(format!("the tunnel did not come up within {}s", startup.as_secs()));
                }
            }
            reason = job.outcome() => return Outcome::failed(format!("tunnel: {reason}")),
        }
        self.hold(job, endpoint, settings, port).await
    }

    async fn hold(
        self: &Arc<Self>,
        mut job: CoreJob,
        endpoint: Option<Endpoint>,
        settings: &Settings,
        inner: u16,
    ) -> Outcome {
        // The tunnel carried a test connection, so the edge is worth coming
        // back to even if Psiphon does not come up behind it.
        let pinned = !settings.pinned_peer.trim().is_empty();
        if let (Some(endpoint), false) = (endpoint, pinned) {
            // Speed is checked before the proxy is handed over, so a slow
            // edge is swapped while the status still says connecting.
            if let Some(reason) = self.judge(settings, endpoint, inner).await {
                return Outcome { reason, held: Duration::ZERO, connected: false, rotate: true };
            }
            self.cache.remember(settings.protocol, settings.ip_version, endpoint);
        }
        let relayed = match self.start_relay(settings, inner).await {
            Ok(relayed) => relayed,
            Err(reason) => return Outcome { reason, held: Duration::ZERO, connected: true, rotate: false },
        };
        let carry = relayed.unwrap_or(inner);
        self.front.point_at(carry);
        let since = Instant::now();
        self.update(|status| {
            status.phase = Phase::Connected;
            status.since = Some(since);
            status.relayed = relayed.is_some();
            status.endpoint = endpoint;
        });
        let via = endpoint.map(|e| format!(" via {}", e.socket())).unwrap_or_default();
        log::info!(target: "freeport", "[+] connected{via}");
        log::info!(target: "freeport", "[*] SOCKS5 127.0.0.1:{} · HTTP 127.0.0.1:{}", self.front.ports().0, self.front.ports().1);

        let _checks = Helper(tokio::spawn(self.clone().watch_exit(settings.clone(), inner, relayed)));
        let _scout = (endpoint.is_some() && !pinned)
            .then(|| Helper(tokio::spawn(self.clone().scout(settings.clone(), endpoint.unwrap(), inner))));

        let mut watch = Watch::new(inner, relayed);
        let reason = loop {
            tokio::time::sleep(POLL).await;
            if job.is_finished() {
                break job.outcome().await;
            }
            if let Some(reason) = self.check_health(&mut watch).await {
                break reason;
            }
        };
        let held = since.elapsed();
        if endpoint.is_some() && held < super::STABLE {
            // An edge that drops a session within seconds is not one to hurry back to.
            self.cache.forget(settings.protocol, settings.ip_version);
        }
        self.front.point_at(0);
        if relayed.is_some() {
            self.relay.stop().await;
        }
        Outcome { reason, held, connected: true, rotate: false }
    }

    /// Starts Psiphon behind the tunnel when region unlock is on, and returns
    /// the port it serves on. Fails closed: with region unlock on, a Psiphon
    /// that does not come up ends the attempt rather than letting traffic
    /// leave from Cloudflare's exit, which sites see as Iran.
    /// Measures the edge through the fresh tunnel; Some(reason) when it is
    /// too slow for video. The upload is measured and the edge shared in
    /// the background, so the connection is not held up by it.
    async fn judge(self: &Arc<Self>, settings: &Settings, endpoint: Endpoint, port: u16) -> Option<String> {
        let measured = match board::measure(port, JUDGE_BYTES, JUDGE_WINDOW, 0).await {
            Ok(measured) => measured,
            Err(e) => {
                log::warn!(target: "freeport", "[-] could not measure {}: {e:#}", endpoint.socket());
                return None;
            }
        };
        log::info!(
            target: "freeport",
            "[*] {} delivers {} KB/s, first byte in {}ms",
            endpoint.socket(), measured.kbps, measured.first_byte_ms
        );
        let tested = SharedEdge { addr: endpoint.ip, port: endpoint.port, rtt: measured.first_byte_ms, kbps: measured.kbps, up: 0 };
        let protocol = settings.protocol;
        let ip = settings.ip_version;
        tokio::spawn(async move {
            let up = board::measure(port, 0, Duration::ZERO, UPLOAD_BYTES).await.map(|m| m.up).unwrap_or(0);
            board::report(protocol, ip, SharedEdge { up, ..tested }, Some(port)).await;
            // Refreshes the kept list through the tunnel for the next start.
            let _ = board::fetch(protocol, ip, Some(port)).await;
        });
        if measured.kbps >= SLOW_KBPS {
            return None;
        }
        self.slow.lock().insert(endpoint.socket());
        self.cache.forget(protocol, ip);
        Some(format!("{} carries only {} KB/s, trying another edge", endpoint.socket(), measured.kbps))
    }

    async fn start_relay(&self, settings: &Settings, inner: u16) -> Result<Option<u16>, String> {
        if !settings.region_unlock {
            return Ok(None);
        }
        self.set_phase(Phase::Unlocking, None);
        let port = ports::free_from(RELAY_BASE).ok_or("no free local port for region unlock")?;
        match self.relay.start(inner, port, &settings.exit_country, RELAY_STARTUP).await {
            Ok(()) => Ok(Some(port)),
            Err(e) => {
                self.relay.stop().await;
                Err(format!("region unlock did not come up: {e:#}"))
            }
        }
    }

    /// Looks up the exit, moves Psiphon off a refused country, asks Claude
    /// whether it would serve, then keeps measuring latency.
    async fn watch_exit(self: Arc<Self>, settings: Settings, inner: u16, relayed: Option<u16>) {
        let carry = relayed.unwrap_or(inner);
        let mut trace = exit::trace(carry).await.ok();

        let refused = trace.as_ref().is_some_and(|t| model::is_refused(&t.country));
        if let (true, Some(port)) = (refused, relayed) {
            if settings.exit_country.is_empty() {
                let country = trace.as_ref().map(|t| t.country.clone()).unwrap_or_default();
                log::warn!(target: "freeport", "[-] psiphon exits in {country}, which services refuse; moving to {}", model::FALLBACK_EXIT);
                self.set_phase(Phase::Unlocking, None);
                match self.relay.start(inner, port, model::FALLBACK_EXIT, RELAY_STARTUP).await {
                    Ok(()) => trace = exit::trace(carry).await.ok(),
                    Err(e) => log::warn!(target: "freeport", "[-] could not move the exit: {e:#}"),
                }
                self.set_phase(Phase::Connected, None);
            }
        }

        if let Some(found) = &trace {
            let note = if relayed.is_some() { " (region unlock)" } else { "" };
            log::info!(target: "freeport", "[*] exit {} {} · colo {}{note}", model::flag(&found.country), found.country, found.colo);
        }
        let claude = exit::claude(carry).await.ok();
        match claude {
            Some(exit::ClaudeReach::Open) => log::info!(target: "freeport", "[+] Claude answers from this exit"),
            Some(exit::ClaudeReach::Refused) => log::warn!(target: "freeport", "[-] Claude refuses this exit's region"),
            None => {}
        }
        self.update(|status| {
            status.exit = trace;
            status.claude = claude;
        });

        loop {
            if let Ok(ms) = exit::latency(carry).await {
                self.update(|status| {
                    status.latency.push(ms);
                    let excess = status.latency.len().saturating_sub(LATENCY_KEPT);
                    status.latency.drain(..excess);
                });
            }
            tokio::time::sleep(LATENCY_EVERY).await;
        }
    }
}

/// Waits for the lanes; once one finds an edge, the rest get a short grace
/// to beat it.
async fn race<const N: usize>(lanes: [Option<JoinHandle<Vec<(Protocol, Endpoint)>>>; N]) -> Vec<(Protocol, Endpoint)> {
    let mut pending: Vec<JoinHandle<Vec<(Protocol, Endpoint)>>> = lanes.into_iter().flatten().collect();
    let mut found = Vec::new();
    let deadline = tokio::time::Instant::now() + AUTO_PING;
    let mut grace_until: Option<tokio::time::Instant> = None;

    while !pending.is_empty() {
        let limit = grace_until.unwrap_or(deadline).min(deadline);
        if tokio::time::Instant::now() >= limit {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
        let mut still = Vec::new();
        for lane in pending {
            if lane.is_finished() {
                if let Ok(more) = lane.await {
                    if !more.is_empty() {
                        grace_until.get_or_insert_with(|| tokio::time::Instant::now() + AUTO_GRACE);
                    }
                    found.extend(more);
                }
            } else {
                still.push(lane);
            }
        }
        pending = still;
    }
    pending.into_iter().for_each(|lane| lane.abort());
    found
}
