//! The public V2Ray collections on GitHub, and the shared pool on the edge
//! board that every Freeport tests into. Fetched over the bare line or,
//! where GitHub is filtered, through a local SOCKS port; the last copy is
//! kept, so a network that blocks both still has configs to test.

use std::fs;
use std::time::{Duration, SystemTime};

use futures::future::join_all;

use super::link::{self, ShareLink};
use super::xray;
use crate::engine::board::{self, BOARD};

/// The configs that answered in the daily census, quickest first.
const CENSUS: (&str, &str, &str) = ("mhammaddawudi/freeport", "configs", "alive.txt");
/// Large, frequently refreshed, mixed-protocol collections.
const SOURCES: [(&str, &str, &str); 6] = [
    ("barry-far/V2ray-Config", "main", "All_Configs_Sub.txt"),
    ("MatinGhanbari/v2ray-configs", "main", "subscriptions/v2ray/all_sub.txt"),
    ("10ium/ScrapeAndCategorize", "main", "output_configs/Vless.txt"),
    ("mahdibland/V2RayAggregator", "master", "sub/sub_merge.txt"),
    ("roosterkid/openproxylist", "main", "V2RAY_RAW.txt"),
    ("Kwinshadow/TelegramV2rayCollector", "main", "sublinks/mix.txt"),
];
const MIN_CENSUS: usize = 50;
const MIN_BODY: usize = 200;
const MAX_AGE: Duration = Duration::from_secs(3 * 3600);
const BARE_TIMEOUT: Duration = Duration::from_secs(6);
const TUNNEL_TIMEOUT: Duration = Duration::from_secs(25);
const MAX_REPORT: usize = 80;

/// One config's test here, for the shared pool.
#[derive(Clone)]
pub struct Report {
    pub link: ShareLink,
    pub ok: bool,
    pub delay_ms: u64,
    pub kbps: u64,
}

fn cache_file() -> Option<std::path::PathBuf> {
    xray::folder().ok().map(|dir| dir.join("feed.txt"))
}

/// Whether the kept copy is missing or older than [`MAX_AGE`].
pub fn is_stale() -> bool {
    cache_file()
        .and_then(|path| fs::metadata(path).ok())
        .and_then(|meta| meta.modified().ok())
        .and_then(|at| SystemTime::now().duration_since(at).ok())
        .is_none_or(|age| age > MAX_AGE)
}

pub fn has_copy() -> bool {
    cache_file().and_then(|path| fs::metadata(path).ok()).is_some_and(|meta| meta.len() > 0)
}

/// Configs from every source: fresh when the copy is stale, over the bare
/// line first and then through each of `via`, else the kept copy.
pub async fn load(via: &[u16]) -> Vec<ShareLink> {
    let mut body = None;
    if is_stale() {
        for route in std::iter::once(None).chain(via.iter().copied().map(Some)) {
            if let Some(fetched) = fetch_via(route).await {
                body = Some(fetched);
                break;
            }
            match route {
                None => log::info!(target: "freeport", "[*] configs: GitHub is out of reach on the bare line"),
                Some(port) => log::info!(target: "freeport", "[*] configs: GitHub is out of reach through port {port}"),
            }
        }
    }
    if let (Some(text), Some(path)) = (&body, cache_file()) {
        let _ = fs::write(path, text);
    }
    let kept = body.is_none();
    let text = body.or_else(|| cache_file().and_then(|path| fs::read_to_string(path).ok())).unwrap_or_default();
    let mut seen = std::collections::HashSet::new();
    let links: Vec<ShareLink> = link::parse_all(&text).into_iter().filter(|l| seen.insert(l.key())).collect();
    log::info!(target: "freeport", "[*] configs: {} usable configs{}", links.len(), if kept { " (kept copy)" } else { "" });
    links
}

/// Refreshes the kept copy through a tunnel that is up anyway, when it is stale.
pub async fn refresh_through(port: u16) {
    if !is_stale() {
        return;
    }
    if let (Some(text), Some(path)) = (fetch_via(Some(port)).await, cache_file()) {
        let _ = fs::write(path, text);
    }
}

/// Best first: the shared pool on the board, else the census from GitHub,
/// else the collections themselves, shuffled so users do not all knock on
/// the same few.
async fn fetch_via(route: Option<u16>) -> Option<String> {
    if let Some(body) = get(&format!("{BOARD}/v1/configs"), route).await {
        let shared = only_links(&body);
        if shared.lines().count() >= MIN_CENSUS {
            log::info!(target: "freeport", "[+] configs: the shared pool");
            return Some(shared);
        }
    }
    if let Some(census) = fetch(CENSUS, route).await.filter(|text| text.lines().count() >= MIN_CENSUS) {
        return Some(census);
    }
    let bodies: Vec<String> = join_all(SOURCES.iter().map(|source| fetch(*source, route))).await.into_iter().flatten().collect();
    if bodies.is_empty() {
        return None;
    }
    let mut lines: Vec<&str> = bodies.iter().flat_map(|b| b.lines()).collect();
    shuffle(&mut lines);
    Some(lines.join("\n"))
}

async fn fetch((repo, branch, path): (&str, &str, &str), route: Option<u16>) -> Option<String> {
    let mirrors = [
        format!("https://raw.githubusercontent.com/{repo}/{branch}/{path}"),
        format!("https://cdn.jsdelivr.net/gh/{repo}@{branch}/{path}"),
        format!("https://fastly.jsdelivr.net/gh/{repo}@{branch}/{path}"),
    ];
    for url in mirrors {
        if let Some(body) = get(&url, route).await.filter(|b| b.len() > MIN_BODY) {
            log::info!(target: "freeport", "[+] configs: {repo}");
            return Some(only_links(&body));
        }
    }
    None
}

fn only_links(body: &str) -> String {
    link::parse_all(body).iter().map(|l| l.raw.as_str()).collect::<Vec<_>>().join("\n")
}

async fn get(url: &str, route: Option<u16>) -> Option<String> {
    let limit = if route.is_some() { TUNNEL_TIMEOUT } else { BARE_TIMEOUT };
    let client = board::client(route, limit).ok()?;
    let response = client.get(url).header("User-Agent", "Mozilla/5.0").send().await.ok()?.error_for_status().ok()?;
    response.text().await.ok()
}

/// Sends this machine's results to the shared pool; false when it was out of reach.
pub async fn share(reports: &[Report], route: Option<u16>) -> bool {
    if reports.is_empty() {
        return true;
    }
    let results: Vec<_> = reports
        .iter()
        .take(MAX_REPORT)
        .map(|r| serde_json::json!({ "link": r.link.raw, "ok": r.ok, "delay": r.delay_ms, "kbps": r.kbps }))
        .collect();
    let Ok(client) = board::client(route, TUNNEL_TIMEOUT) else { return false };
    matches!(
        client.post(format!("{BOARD}/v1/configs")).json(&serde_json::json!({ "results": results })).send().await,
        Ok(response) if response.status().is_success()
    )
}

/// A cheap shuffle, seeded by the clock: spreading the load needs no more.
fn shuffle<T>(items: &mut [T]) {
    let mut seed = SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(7) | 1;
    for i in (1..items.len()).rev() {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        items.swap(i, (seed % (i as u64 + 1)) as usize);
    }
}
