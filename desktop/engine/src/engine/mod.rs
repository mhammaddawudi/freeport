//! Drives one session: route discovery, tunnel, region unlock, and the
//! reconnect loop around them. The dashboard and the headless mode both
//! watch [`Status`] and call [`Engine::connect`] / [`Engine::disconnect`].

mod attempt;
mod board;
mod cache;
mod scout;
mod core;
mod health;
mod probe;
mod relay;
pub mod configs;
pub mod tor;
pub(crate) mod tuner;

use std::sync::Arc;
use std::time::{Duration, Instant};

use warp::api::Endpoint;
use parking_lot::Mutex;
use tokio::sync::watch;
use tokio::task::JoinHandle;

use crate::model::Protocol;
use crate::net::exit::{ClaudeReach, Trace};
use crate::net::front::Front;
use crate::settings::Settings;

pub use relay::Relay;

#[derive(Debug, Clone, PartialEq)]
pub enum Phase {
    Idle,
    Preparing,
    Scanning,
    Connecting,
    /// The tunnel is up and Psiphon is being started behind it.
    Unlocking,
    Connected,
    Reconnecting { attempt: u32, reason: String },
    Disconnecting,
}

impl Phase {
    pub fn is_engaged(&self) -> bool {
        !matches!(self, Phase::Idle)
    }
}

#[derive(Debug, Clone)]
pub struct Status {
    pub phase: Phase,
    /// The protocol carrying (or about to carry) the session — never Auto.
    pub protocol: Option<Protocol>,
    pub via_auto: bool,
    pub endpoint: Option<Endpoint>,
    pub since: Option<Instant>,
    pub relayed: bool,
    pub exit: Option<Trace>,
    pub claude: Option<ClaudeReach>,
    /// Round trips through the whole session, newest last.
    pub latency: Vec<u64>,
}

impl Default for Status {
    fn default() -> Self {
        Status {
            phase: Phase::Idle,
            protocol: None,
            via_auto: false,
            endpoint: None,
            since: None,
            relayed: false,
            exit: None,
            claude: None,
            latency: Vec::new(),
        }
    }
}

pub struct Engine {
    pub front: Front,
    status: watch::Sender<Status>,
    session: Mutex<Option<JoinHandle<()>>>,
    /// Serialises connect and disconnect, so quick key presses cannot leave
    /// two sessions alive.
    control: tokio::sync::Mutex<()>,
    pub(crate) core: core::Core,
    pub(crate) cache: cache::EndpointCache,
    pub(crate) relay: Relay,
    pub(crate) tor: tor::Tor,
    pub(crate) configs: configs::Configs,
    /// Edges found too slow for video this session; scans and the shared
    /// list skip them.
    pub(crate) slow: Mutex<std::collections::HashSet<std::net::SocketAddr>>,
    /// Protocols that answered Automatic's scan this session but carried
    /// nothing; the next round tries them last.
    pub(crate) benched: Mutex<Vec<crate::model::Protocol>>,
    pub(crate) tuner: tuner::Tuner,
    /// Each protocol's last round trip on this network in ms; -1 when it did not answer.
    pub(crate) reach: Mutex<std::collections::HashMap<Protocol, i64>>,
    pub(crate) probing: std::sync::atomic::AtomicBool,
}

impl Engine {
    pub fn new(front: Front) -> Arc<Engine> {
        Arc::new(Engine {
            front,
            status: watch::channel(Status::default()).0,
            session: Mutex::new(None),
            control: tokio::sync::Mutex::new(()),
            core: core::Core::default(),
            cache: cache::EndpointCache::load(),
            relay: Relay::default(),
            tor: tor::Tor::default(),
            configs: configs::Configs::default(),
            slow: Mutex::new(Default::default()),
            benched: Mutex::new(Vec::new()),
            reach: Mutex::new(Default::default()),
            probing: Default::default(),
            tuner: if cfg!(test) { tuner::Tuner::default() } else { tuner::Tuner::load() },
        })
    }

    pub fn watch(&self) -> watch::Receiver<Status> {
        self.status.subscribe()
    }

    pub(crate) fn update(&self, change: impl FnOnce(&mut Status)) {
        self.status.send_modify(change);
    }

    pub(crate) fn set_phase(&self, phase: Phase, protocol: Option<Protocol>) {
        self.update(|status| {
            status.phase = phase;
            if protocol.is_some() {
                status.protocol = protocol;
            }
        });
    }

    /// Starts a session with `settings`, replacing any that is running.
    pub async fn connect(self: &Arc<Self>, settings: Settings) {
        let _control = self.control.lock().await;
        self.probing.store(false, std::sync::atomic::Ordering::SeqCst);
        self.stop_session().await;
        let engine = self.clone();
        let handle = tokio::spawn(async move { engine.run_session(settings).await });
        *self.session.lock() = Some(handle);
    }

    pub async fn disconnect(&self) {
        let _control = self.control.lock().await;
        self.stop_session().await;
    }

    async fn stop_session(&self) {
        let running = self.session.lock().take();
        let Some(handle) = running else { return };
        if !handle.is_finished() {
            self.set_phase(Phase::Disconnecting, None);
            handle.abort();
            let _ = handle.await;
            log::info!(target: "freeport", "[*] disconnected");
        }
        // Aborting dropped the core job; Psiphon is a child process and is
        // stopped here rather than left to the drop.
        self.relay.stop().await;
        self.tor.stop().await;
        self.configs.stop().await;
        self.front.point_at(0);
        self.status.send_replace(Status::default());
    }

    async fn run_session(self: Arc<Self>, chosen: Settings) {
        let mut attempt: u32 = 0;
        let mut fallback: usize = 0;
        let mut rotations: u32 = 0;
        self.slow.lock().clear();
        self.benched.lock().clear();
        loop {
            let alternatives = chosen.protocol.alternatives();
            let current = match fallback {
                0 => chosen.clone(),
                n => chosen.with_protocol(alternatives[n - 1]),
            };
            if fallback > 0 {
                log::info!(
                    target: "freeport",
                    "[*] {} is not getting through, trying {}",
                    chosen.protocol.name(),
                    current.protocol.name()
                );
            }
            let outcome = self.attempt_once(&current).await;
            self.front.point_at(0);
            // A slow edge is swapped at once, while the status still says
            // connecting, rather than after a backoff.
            if outcome.rotate && rotations < MAX_ROTATIONS {
                rotations += 1;
                continue;
            }
            rotations = 0;

            if outcome.held >= STABLE {
                attempt = 0;
                fallback = 0;
            }
            attempt += 1;
            if attempt % ATTEMPTS_PER_TRANSPORT == 0 {
                fallback = (fallback + 1) % (alternatives.len() + 1);
            }
            // Never give up on its own: drops usually come from a network
            // change or a sleeping Mac, and fix themselves.
            let wait = (BACKOFF * 2u32.pow((attempt - 1).min(6))).min(MAX_BACKOFF);
            log::warn!(
                target: "freeport",
                "[-] {} — retrying in {}s (attempt {attempt})",
                outcome.reason,
                wait.as_secs()
            );
            self.update(|status| {
                *status = Status {
                    phase: Phase::Reconnecting { attempt, reason: outcome.reason.clone() },
                    protocol: Some(current.protocol),
                    ..Status::default()
                }
            });
            tokio::time::sleep(wait).await;
        }
    }
}

/// Why an attempt ended, and how long it carried traffic.
pub(crate) struct Outcome {
    pub reason: String,
    pub held: Duration,
    pub connected: bool,
    /// The edge came up but was too slow; try another straight away.
    pub rotate: bool,
}

impl Outcome {
    pub fn failed(reason: impl Into<String>) -> Outcome {
        Outcome { reason: reason.into(), held: Duration::ZERO, connected: false, rotate: false }
    }
}

const BACKOFF: Duration = Duration::from_secs(1);
const MAX_BACKOFF: Duration = Duration::from_secs(60);
const ATTEMPTS_PER_TRANSPORT: u32 = 2;
const STABLE: Duration = Duration::from_secs(30);
const MAX_ROTATIONS: u32 = 2;
