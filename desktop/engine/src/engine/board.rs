//! The shared pool of fast edges, the same one the phone app uses.
//!
//! Every connection measures its edge's real download and upload and reports
//! them; before scanning, the engine tries the fastest edges anyone has
//! measured. The board is often blocked from inside Iran, so the list fetched
//! through a tunnel is kept on disk for the next start.

use std::fs;
use std::net::IpAddr;
use std::path::PathBuf;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{bail, Result};
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use warp::api::Endpoint;

use crate::model::{IpVersion, Protocol};
use crate::settings;

const BOARD: &str = "https://freeport-edge-board.freeport-mhd.workers.dev";
const DIRECT_TIMEOUT: Duration = Duration::from_millis(1_500);
const TUNNEL_TIMEOUT: Duration = Duration::from_secs(5);
const KEEP: Duration = Duration::from_secs(3 * 24 * 3600);
const MAX_EDGES: usize = 6;

const REEL_BYTES: u64 = 1_000_000;
const POST_BYTES: u64 = 250_000;
const UNMEASURED_MS: u64 = 60_000;
const UNKNOWN_UP_MS: u64 = 500;

/// Under this an edge cannot carry video; it is neither tried nor kept.
pub const SLOW_KBPS: u64 = 300;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct SharedEdge {
    pub addr: IpAddr,
    pub port: u16,
    pub rtt: u64,
    pub kbps: u64,
    #[serde(default)]
    pub up: u64,
}

impl SharedEdge {
    pub fn endpoint(&self) -> Endpoint {
        Endpoint { ip: self.addr, port: self.port, rtt_ms: self.rtt }
    }

    /// Roughly how long a reel takes to start and a photo to post.
    pub fn reel_ms(&self) -> u64 {
        let down = if self.kbps > 0 { REEL_BYTES * 1000 / (self.kbps * 1024) } else { UNMEASURED_MS };
        let up = if self.up > 0 { POST_BYTES * 1000 / (self.up * 1024) } else { UNKNOWN_UP_MS };
        self.rtt + down + up
    }
}

#[derive(Deserialize)]
struct Answer {
    #[serde(default)]
    edges: Vec<SharedEdge>,
}

#[derive(Serialize, Deserialize)]
struct Kept {
    at: u64,
    edges: Vec<SharedEdge>,
}

pub fn proto_id(protocol: Protocol) -> Option<&'static str> {
    match protocol {
        Protocol::MasqueH2 => Some("masque-h2"),
        Protocol::MasqueH3 => Some("masque-h3"),
        Protocol::WireGuard => Some("wireguard"),
        _ => None,
    }
}

fn fam(ip: IpVersion) -> &'static str {
    match ip {
        IpVersion::V4 => "4",
        IpVersion::V6 => "6",
        IpVersion::Both => "dual",
    }
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn kept_file(proto: &str, ip: IpVersion) -> Option<PathBuf> {
    settings::sub_dir("shared").ok().map(|dir| dir.join(format!("{proto}.{}.json", fam(ip))))
}

fn client(socks: Option<u16>, timeout: Duration) -> Result<reqwest::Client> {
    let mut builder = reqwest::Client::builder().timeout(timeout).connect_timeout(timeout);
    builder = match socks {
        Some(port) => builder.proxy(reqwest::Proxy::all(format!("socks5h://127.0.0.1:{port}"))?),
        None => builder.no_proxy(),
    };
    Ok(builder.build()?)
}

/// Only Cloudflare's edge ranges, whatever the board says.
fn is_cloudflare_edge(ip: &IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let o = v4.octets();
            (o[0] == 162 && o[1] == 159) || (o[0] == 188 && o[1] == 114 && (96..=99).contains(&o[2]))
        }
        IpAddr::V6(v6) => v6.segments()[0] == 0x2606 && v6.segments()[1] == 0x4700,
    }
}

/// The fastest edges shared for this protocol, or the last list kept here.
pub async fn fetch(protocol: Protocol, ip: IpVersion, socks: Option<u16>) -> Vec<SharedEdge> {
    let Some(proto) = proto_id(protocol) else { return Vec::new() };
    let timeout = if socks.is_some() { TUNNEL_TIMEOUT } else { DIRECT_TIMEOUT };
    let url = format!("{BOARD}/v1/edges?p={proto}&ip={}&net=x", fam(ip));
    let fresh = async {
        let answer: Answer = client(socks, timeout)?.get(&url).send().await?.error_for_status()?.json().await?;
        anyhow::Ok(answer.edges)
    };
    let edges = match tokio::time::timeout(timeout + Duration::from_secs(1), fresh).await {
        Ok(Ok(edges)) => {
            if let (Some(path), Ok(text)) = (kept_file(proto, ip), serde_json::to_string(&Kept { at: now_secs(), edges: edges.clone() })) {
                let _ = fs::write(path, text);
            }
            edges
        }
        _ => kept_file(proto, ip)
            .and_then(|path| fs::read_to_string(path).ok())
            .and_then(|text| serde_json::from_str::<Kept>(&text).ok())
            .filter(|kept| now_secs().saturating_sub(kept.at) < KEEP.as_secs())
            .map(|kept| kept.edges)
            .unwrap_or_default(),
    };
    edges.into_iter().filter(|e| e.port > 0 && is_cloudflare_edge(&e.addr)).take(MAX_EDGES).collect()
}

/// Shares how an edge did; failures only cost a log line.
pub async fn report(protocol: Protocol, ip: IpVersion, edge: SharedEdge, socks: Option<u16>) {
    let Some(proto) = proto_id(protocol) else { return };
    let body = serde_json::json!({
        "p": proto, "ip": fam(ip), "net": "x",
        "addr": edge.addr.to_string(), "port": edge.port, "rtt": edge.rtt, "kbps": edge.kbps, "up": edge.up,
    });
    let sent = async {
        client(socks, TUNNEL_TIMEOUT)?.post(format!("{BOARD}/v1/report")).json(&body).send().await?;
        anyhow::Ok(())
    };
    if let Err(e) = sent.await {
        log::debug!(target: "freeport", "[*] could not share the edge: {e:#}");
    }
}

/// What a short transfer through a proxy showed.
#[derive(Debug, Clone, Copy)]
pub struct Measured {
    pub kbps: u64,
    pub up: u64,
    pub first_byte_ms: u64,
}

/// Downloads up to `bytes` within `window`, then uploads `up_bytes`.
pub async fn measure(socks: u16, bytes: usize, window: Duration, up_bytes: usize) -> Result<Measured> {
    let client = client(Some(socks), Duration::from_secs(8))?;
    let opened = Instant::now();
    let response = client
        .get(format!("https://speed.cloudflare.com/__down?bytes={bytes}"))
        .send()
        .await?
        .error_for_status()?;
    let mut stream = response.bytes_stream();
    let (mut total, mut first, mut warm) = (0u64, None, None::<(Instant, u64)>);
    let deadline = Instant::now() + window;
    while Instant::now() < deadline {
        let next = tokio::time::timeout_at(deadline.into(), stream.next()).await;
        let Ok(Some(Ok(chunk))) = next else { break };
        first.get_or_insert_with(|| opened.elapsed().as_millis() as u64);
        total += chunk.len() as u64;
        if warm.is_none() && total >= 64 * 1024 {
            warm = Some((Instant::now(), total));
        }
    }
    let Some(first_byte_ms) = first else { bail!("nothing came through") };
    let kbps = warm
        .map(|(at, base)| {
            let ms = at.elapsed().as_millis().max(1) as u64;
            (total - base) * 1000 / ms / 1024
        })
        .unwrap_or(0);
    let up = if up_bytes > 0 { upload(&client, up_bytes).await.unwrap_or(0) } else { 0 };
    Ok(Measured { kbps, up, first_byte_ms })
}

/// Timed until the far end answers, so local buffers do not flatter it.
async fn upload(client: &reqwest::Client, bytes: usize) -> Result<u64> {
    let started = Instant::now();
    client.post("https://speed.cloudflare.com/__up").body(vec![0u8; bytes]).send().await?.error_for_status()?;
    let ms = started.elapsed().as_millis().max(1) as u64;
    Ok(bytes as u64 * 1000 / ms / 1024)
}
