//! The sieve's three steps: a TCP knock on every server, a real request
//! through each config that answered, and a download through the quickest.

use std::sync::Arc;
use std::time::{Duration, Instant};

use futures::future::join_all;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Semaphore;
use tokio::time::timeout;

use super::link::ShareLink;
use super::xray::{self, Route};
use crate::engine::board;
use crate::net::ports;

const KNOCK_PARALLEL: usize = 256;
const KNOCK_TIMEOUT: Duration = Duration::from_secs(2);
const ANSWER_TIMEOUT: Duration = Duration::from_secs(8);
const SPEED_BYTES: usize = 3_000_000;
/// Free local ports for test inbounds are searched for from here.
const TEST_BASE: u16 = 43_819;

/// A config that carried a request, how quickly, and its download once measured.
#[derive(Clone)]
pub struct Healthy {
    pub link: ShareLink,
    pub delay_ms: u64,
    pub kbps: u64,
}

/// Servers that accept a TCP connection, quickest first; stops at `want` or `budget`.
pub async fn knock(links: Vec<ShareLink>, want: usize, budget: Duration) -> Vec<ShareLink> {
    let gate = Arc::new(Semaphore::new(KNOCK_PARALLEL));
    let found = Arc::new(parking_lot::Mutex::new(Vec::<(u64, ShareLink)>::new()));
    let tasks = links.into_iter().map(|link| {
        let gate = gate.clone();
        let found = found.clone();
        async move {
            if found.lock().len() >= want {
                return;
            }
            let Ok(_permit) = gate.acquire().await else { return };
            let started = Instant::now();
            let address = format!("{}:{}", link.host, link.port);
            if let Ok(Ok(_)) = timeout(KNOCK_TIMEOUT, TcpStream::connect(address)).await {
                found.lock().push((started.elapsed().as_millis() as u64, link));
            }
        }
    });
    let _ = timeout(budget, join_all(tasks)).await;
    let mut open = std::mem::take(&mut *found.lock());
    open.sort_by_key(|(ms, _)| *ms);
    open.into_iter().map(|(_, link)| link).take(want).collect()
}

/// Every config of `links` that carries a request; a batch Xray refuses is split.
pub async fn probe(links: &[ShareLink]) -> Vec<Healthy> {
    if links.is_empty() {
        return Vec::new();
    }
    let Some(routes) = routes_for(links) else { return Vec::new() };
    match xray::start(&routes, "probe", false).await {
        Ok(_running) => {
            let answers = join_all(routes.iter().map(|route| answers(route.port))).await;
            routes
                .into_iter()
                .zip(answers)
                .filter_map(|(route, delay)| delay.map(|delay_ms| Healthy { link: route.link, delay_ms, kbps: 0 }))
                .collect()
        }
        Err(_) if links.len() == 1 => Vec::new(),
        Err(_) => {
            let (left, right) = links.split_at(links.len() / 2);
            let mut found = Box::pin(probe(left)).await;
            found.extend(Box::pin(probe(right)).await);
            found
        }
    }
}

/// Each config's download, one after another, `window` each.
pub async fn measure(healthy: Vec<Healthy>, window: Duration) -> Vec<Healthy> {
    let links: Vec<ShareLink> = healthy.iter().map(|h| h.link.clone()).collect();
    let Some(routes) = routes_for(&links) else { return healthy };
    let Ok(_running) = xray::start(&routes, "speed", false).await else { return healthy };
    let mut measured = Vec::with_capacity(healthy.len());
    for (route, mut h) in routes.iter().zip(healthy) {
        h.kbps = board::measure(route.port, SPEED_BYTES, window, 0).await.map(|m| m.kbps).unwrap_or(0);
        log::info!(target: "freeport", "[*] configs: {} carries {} KB/s ({}ms)", h.link.label(), h.kbps, h.delay_ms);
        measured.push(h);
    }
    measured
}

fn routes_for(links: &[ShareLink]) -> Option<Vec<Route>> {
    let mut port = TEST_BASE;
    let mut routes = Vec::with_capacity(links.len());
    for link in links {
        port = ports::free_from(port)?;
        routes.push(Route { link: link.clone(), port });
        port += 1;
    }
    Some(routes)
}

/// A plain HTTP request through the SOCKS port, timed to its answer. Xray
/// grants a SOCKS connect before it reaches anything, so only an answer
/// from the far side says the config works.
pub async fn answers(port: u16) -> Option<u64> {
    timeout(ANSWER_TIMEOUT, async {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).await.ok()?;
        stream.write_all(&[5, 1, 0]).await.ok()?;
        let mut reply = [0u8; 2];
        stream.read_exact(&mut reply).await.ok()?;
        let host = b"cp.cloudflare.com";
        let mut request = vec![5, 1, 0, 3, host.len() as u8];
        request.extend_from_slice(host);
        request.extend_from_slice(&80u16.to_be_bytes());
        stream.write_all(&request).await.ok()?;
        let mut head = [0u8; 10];
        stream.read_exact(&mut head).await.ok()?;
        if head[1] != 0 {
            return None;
        }
        let started = Instant::now();
        stream.write_all(b"GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nConnection: close\r\n\r\n").await.ok()?;
        let mut status = [0u8; 12];
        stream.read_exact(&mut status).await.ok()?;
        let code = &status[9..12];
        (code == b"204" || code == b"200").then(|| started.elapsed().as_millis() as u64)
    })
    .await
    .ok()
    .flatten()
}
