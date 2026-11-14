//! The configs that work, fastest download first, kept between runs. Every
//! test also goes to the shared pool on the edge board, so each machine's
//! work helps the others; what it sends waits until the board is reachable.

use std::collections::HashMap;
use std::fs;
use std::time::{Duration, SystemTime};

use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

use super::feed::{self, Report};
use super::link::{self, ShareLink};
use super::tester::{self, Healthy};
use super::xray;

const TTL: Duration = Duration::from_secs(24 * 3600);
const REFRESH: Duration = Duration::from_secs(3 * 3600);
const FAILED_FOR: Duration = Duration::from_secs(30 * 60);
const MIN_HEALTHY: usize = 3;
const KNOCK_MAX: usize = 4_000;
const BATCH: usize = 80;
const KNOCK_BUDGET: Duration = Duration::from_secs(20);
const MIN_TEST: Duration = Duration::from_secs(60);
const MAX_POOLED: usize = 60;
const MAX_FAILED_REPORTS: usize = 40;
/// A download this quick carries video without stalling.
const FAST_KBPS: u64 = 400;

/// How far one sieve goes.
#[derive(Clone, Copy)]
pub struct Depth {
    knock: usize,
    healthy: usize,
    speed_tested: usize,
    window: Duration,
    speed_reserve: Duration,
}

pub const QUICK: Depth = Depth { knock: 600, healthy: 30, speed_tested: 8, window: Duration::from_millis(3_500), speed_reserve: Duration::from_secs(35) };
pub const DEEP: Depth = Depth { knock: 1_200, healthy: 60, speed_tested: 12, window: Duration::from_secs(5), speed_reserve: Duration::from_secs(70) };

#[derive(Clone, Serialize, Deserialize)]
struct Entry {
    link: String,
    delay: u64,
    kbps: u64,
    at: u64,
}

#[derive(Default, Serialize, Deserialize)]
struct Saved {
    entries: Vec<Entry>,
    /// Config key → when it failed here, seconds since the epoch.
    failures: HashMap<String, u64>,
}

#[derive(Default)]
pub struct Pool {
    saved: Mutex<Saved>,
    pending: Mutex<Vec<Report>>,
}

fn now() -> u64 {
    SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

impl Pool {
    pub fn load() -> Pool {
        let saved = xray::folder()
            .ok()
            .and_then(|dir| fs::read(dir.join("pool.json")).ok())
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        Pool { saved: Mutex::new(saved), pending: Mutex::default() }
    }

    fn save(&self) {
        let mut saved = self.saved.lock();
        let cutoff = now().saturating_sub(FAILED_FOR.as_secs());
        saved.failures.retain(|_, at| *at > cutoff);
        if let (Ok(dir), Ok(bytes)) = (xray::folder(), serde_json::to_vec(&*saved)) {
            let _ = fs::write(dir.join("pool.json"), bytes);
        }
    }

    /// Configs to try, best download first.
    pub fn candidates(&self) -> Vec<(ShareLink, u64)> {
        let saved = self.saved.lock();
        let at = now();
        let mut fresh: Vec<&Entry> = saved
            .entries
            .iter()
            .filter(|e| at.saturating_sub(e.at) < TTL.as_secs())
            .collect();
        fresh.sort_by(|a, b| b.kbps.cmp(&a.kbps).then(a.delay.cmp(&b.delay)));
        fresh
            .into_iter()
            .filter_map(|e| link::parse(&e.link).map(|l| (l, e.delay)))
            .filter(|(l, _)| saved.failures.get(&l.key()).is_none_or(|t| at.saturating_sub(*t) > FAILED_FOR.as_secs()))
            .collect()
    }

    /// The quickest pooled config's round trip.
    pub fn best_delay(&self) -> Option<u64> {
        self.candidates().iter().map(|(_, d)| *d).filter(|d| *d > 0).min()
    }

    pub fn is_stale(&self) -> bool {
        let newest = self.saved.lock().entries.iter().map(|e| e.at).max().unwrap_or(0);
        self.candidates().len() < MIN_HEALTHY || now().saturating_sub(newest) > REFRESH.as_secs()
    }

    /// Whether too few pooled configs have had their download measured fast.
    pub fn is_shallow(&self) -> bool {
        self.saved.lock().entries.iter().filter(|e| e.kbps >= FAST_KBPS).count() < DEEP.speed_tested / 2
    }

    pub fn mark_failed(&self, link: &ShareLink) {
        self.saved.lock().failures.insert(link.key(), now());
        self.pending.lock().push(Report { link: link.clone(), ok: false, delay_ms: 0, kbps: 0 });
        self.save();
    }

    pub fn mark_good(&self, link: &ShareLink, delay_ms: u64) {
        self.pending.lock().push(Report { link: link.clone(), ok: true, delay_ms, kbps: 0 });
    }

    /// Sends what waits to the shared pool, over the bare line or through `route`.
    pub async fn share(&self, route: Option<u16>) {
        let batch = self.pending.lock().clone();
        if batch.is_empty() {
            return;
        }
        if feed::share(&batch, route).await {
            let sent = batch.len();
            let mut pending = self.pending.lock();
            let sent_now = sent.min(pending.len());
            pending.drain(..sent_now);
            log::info!(target: "freeport", "[*] configs: {sent} results shared with the pool");
        }
    }

    /// Sieves the lists for configs that work here: TCP knock, a real
    /// request through each, then the download of the quickest few.
    pub async fn refresh(&self, budget: Duration, via: &[u16], depth: Depth) -> usize {
        let begun = std::time::Instant::now();
        let known: Vec<ShareLink> = self.saved.lock().entries.iter().filter_map(|e| link::parse(&e.link)).collect();
        let fresh = feed::load(via).await;
        if fresh.is_empty() && known.is_empty() {
            return 0;
        }
        let keys: std::collections::HashSet<String> = known.iter().map(ShareLink::key).collect();
        let pile: Vec<ShareLink> = known.into_iter().chain(fresh.into_iter().filter(|l| !keys.contains(&l.key())).take(KNOCK_MAX)).collect();
        let deadline = (begun + budget).max(std::time::Instant::now() + MIN_TEST);
        log::info!(target: "freeport", "[*] configs: knocking on {} servers", pile.len());
        let open = tester::knock(pile, depth.knock, KNOCK_BUDGET).await;
        log::info!(target: "freeport", "[*] configs: {} servers answered; testing them through xray", open.len());

        let mut healthy: Vec<Healthy> = Vec::new();
        let mut tried: Vec<ShareLink> = Vec::new();
        for batch in open.chunks(BATCH) {
            if healthy.len() >= depth.healthy || std::time::Instant::now() + depth.speed_reserve > deadline {
                break;
            }
            healthy.extend(tester::probe(batch).await);
            tried.extend_from_slice(batch);
            log::info!(target: "freeport", "[*] configs: {} working so far", healthy.len());
        }
        let working: std::collections::HashSet<String> = healthy.iter().map(|h| h.link.key()).collect();
        {
            let mut saved = self.saved.lock();
            let mut pending = self.pending.lock();
            for link in tried.iter().filter(|l| !working.contains(&l.key())).take(MAX_FAILED_REPORTS) {
                saved.failures.insert(link.key(), now());
                pending.push(Report { link: link.clone(), ok: false, delay_ms: 0, kbps: 0 });
            }
        }
        if healthy.is_empty() {
            log::warn!(target: "freeport", "[-] configs: none of the public configs carries traffic on this network");
            self.save();
            self.share(None).await;
            return 0;
        }
        healthy.sort_by_key(|h| h.delay_ms);
        let rest = healthy.split_off(depth.speed_tested.min(healthy.len()));
        // The download decides: a quick answer often hides a line too thin for video.
        let mut measured = tester::measure(healthy, depth.window).await;
        measured.extend(rest);
        measured.sort_by(|a, b| b.kbps.cmp(&a.kbps).then(a.delay_ms.cmp(&b.delay_ms)));
        let at = now();
        {
            let mut saved = self.saved.lock();
            let kept: Vec<Entry> = saved
                .entries
                .iter()
                .filter(|e| link::parse(&e.link).is_some_and(|l| !working.contains(&l.key())) && at.saturating_sub(e.at) < TTL.as_secs())
                .cloned()
                .collect();
            saved.entries = measured
                .iter()
                .map(|h| Entry { link: h.link.raw.clone(), delay: h.delay_ms, kbps: h.kbps, at })
                .chain(kept)
                .take(MAX_POOLED)
                .collect();
            self.pending.lock().extend(measured.iter().map(|h| Report { link: h.link.clone(), ok: true, delay_ms: h.delay_ms, kbps: h.kbps }));
        }
        self.save();
        self.share(None).await;
        log::info!(target: "freeport", "[+] configs: {} working configs pooled, best {} KB/s", measured.len(), measured[0].kbps);
        measured.len()
    }
}
