//! The proxies apps talk to: SOCKS5 and HTTP on fixed ports.
//!
//! Whatever carries the session behind them — the core's own SOCKS port, or
//! Psiphon's when region unlock is on — moves to a fresh port on every
//! reconnect. The front ports never move, so a browser, the system proxy or
//! `HTTPS_PROXY` set once keeps working, and every byte passes one place
//! where it can be counted for the dashboard.

use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::Arc;

use anyhow::{Context, Result};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use parking_lot::Mutex;
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

use super::{http, socks};

#[derive(Default)]
pub struct Stats {
    pub up: AtomicU64,
    pub down: AtomicU64,
    pub open: AtomicU64,
}

#[derive(Clone)]
pub struct Front {
    upstream: Arc<AtomicU16>,
    pub stats: Arc<Stats>,
    ports: Arc<Mutex<Bound>>,
}

/// The listening ports and the tasks accepting on them.
struct Bound {
    socks: Listening,
    http: Listening,
}

struct Listening {
    port: u16,
    task: Option<JoinHandle<()>>,
}

impl Listening {
    const NONE: Listening = Listening { port: 0, task: None };
}

impl Front {
    /// Binds both ports; nothing is carried until [`Front::point_at`].
    pub async fn bind(socks_port: u16, http_port: u16) -> Result<Front> {
        let socks = listen(socks_port).await?;
        let http = listen(http_port).await?;
        let front = Front {
            upstream: Arc::new(AtomicU16::new(0)),
            stats: Arc::new(Stats::default()),
            ports: Arc::new(Mutex::new(Bound { socks: Listening::NONE, http: Listening::NONE })),
        };
        let bound = Bound { socks: front.serve_on(socks, Kind::Socks)?, http: front.serve_on(http, Kind::Http)? };
        *front.ports.lock() = bound;
        Ok(front)
    }

    fn serve_on(&self, listener: TcpListener, kind: Kind) -> Result<Listening> {
        let port = listener.local_addr()?.port();
        Ok(Listening { port, task: Some(tokio::spawn(serve(listener, self.clone(), kind))) })
    }

    /// Moves the proxies to new ports while running. The new ports are taken
    /// before the old ones are let go, so a port that cannot be had leaves
    /// everything as it was; connections already open carry on either way.
    pub async fn rebind(&self, socks_port: u16, http_port: u16) -> Result<()> {
        let (old_socks, old_http) = self.ports();
        let socks = match socks_port != old_socks {
            true => Some(listen(socks_port).await?),
            false => None,
        };
        let http = match http_port != old_http {
            true => Some(listen(http_port).await?),
            false => None,
        };
        let socks = socks.map(|listener| self.serve_on(listener, Kind::Socks)).transpose()?;
        let http = http.map(|listener| self.serve_on(listener, Kind::Http)).transpose()?;
        let mut guard = self.ports.lock();
        let bound = &mut *guard;
        for (slot, fresh) in [(&mut bound.socks, socks), (&mut bound.http, http)] {
            if let Some(fresh) = fresh {
                if let Some(task) = std::mem::replace(slot, fresh).task {
                    task.abort();
                }
            }
        }
        log::info!(target: "freeport", "[+] proxies on SOCKS5 127.0.0.1:{socks_port} · HTTP 127.0.0.1:{http_port}");
        Ok(())
    }

    /// The SOCKS5 and HTTP ports being listened on.
    pub fn ports(&self) -> (u16, u16) {
        let bound = self.ports.lock();
        (bound.socks.port, bound.http.port)
    }

    /// Sends new connections to the SOCKS5 proxy on this local port; 0 refuses them.
    pub fn point_at(&self, port: u16) {
        self.upstream.store(port, Ordering::SeqCst);
    }

    pub fn upstream(&self) -> u16 {
        self.upstream.load(Ordering::SeqCst)
    }
}

async fn listen(port: u16) -> Result<TcpListener> {
    TcpListener::bind(("127.0.0.1", port))
        .await
        .with_context(|| format!("127.0.0.1:{port} is taken; is another Freeport or proxy running?"))
}

#[derive(Clone, Copy)]
enum Kind {
    Socks,
    Http,
}

async fn serve(listener: TcpListener, front: Front, kind: Kind) {
    loop {
        let Ok((client, _)) = listener.accept().await else { continue };
        let front = front.clone();
        tokio::spawn(async move {
            let port = front.upstream();
            if port == 0 {
                return;
            }
            let _ = client.set_nodelay(true);
            front.stats.open.fetch_add(1, Ordering::Relaxed);
            let outcome = match kind {
                Kind::Socks => relay_socks(client, port, &front.stats).await,
                Kind::Http => relay_http(client, port, &front.stats).await,
            };
            front.stats.open.fetch_sub(1, Ordering::Relaxed);
            if let Err(e) = outcome {
                log::debug!(target: "freeport", "[-] proxy connection ended: {e:#}");
            }
        });
    }
}

/// SOCKS5 speaks the same protocol on both sides, so it is passed through untouched.
async fn relay_socks(client: TcpStream, port: u16, stats: &Stats) -> Result<()> {
    let upstream = TcpStream::connect(("127.0.0.1", port)).await.context("the tunnel's proxy is not answering")?;
    let _ = upstream.set_nodelay(true);
    splice(client, upstream, stats).await
}

async fn relay_http(mut client: TcpStream, port: u16, stats: &Stats) -> Result<()> {
    let request = http::read_head(&mut client).await?;
    let mut upstream = TcpStream::connect(("127.0.0.1", port)).await.context("the tunnel's proxy is not answering")?;
    let _ = upstream.set_nodelay(true);
    if let Err(e) = socks::connect(&mut upstream, &request.host, request.port).await {
        let _ = client.write_all(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n").await;
        return Err(e);
    }
    match &request.forward {
        // CONNECT: tell the client the tunnel is open, then stay out of the way.
        None => client.write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n").await?,
        Some(head) => upstream.write_all(head).await?,
    }
    if !request.rest.is_empty() {
        upstream.write_all(&request.rest).await?;
        stats.up.fetch_add(request.rest.len() as u64, Ordering::Relaxed);
    }
    splice(client, upstream, stats).await
}

async fn splice(client: TcpStream, upstream: TcpStream, stats: &Stats) -> Result<()> {
    let (client_read, client_write) = client.into_split();
    let (upstream_read, upstream_write) = upstream.into_split();
    let up = pump(client_read, upstream_write, &stats.up);
    let down = pump(upstream_read, client_write, &stats.down);
    // Either side closing ends both: a half-open proxy connection only leaks.
    tokio::select! {
        r = up => r,
        r = down => r,
    }
}

async fn pump<R, W>(mut from: R, mut to: W, counter: &AtomicU64) -> Result<()>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let mut buffer = vec![0u8; 32 * 1024];
    loop {
        let n = from.read(&mut buffer).await?;
        if n == 0 {
            let _ = to.shutdown().await;
            return Ok(());
        }
        to.write_all(&buffer[..n]).await?;
        counter.fetch_add(n as u64, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod rebind_tests {
    use super::*;
    use crate::net::ports;

    /// Two free ports from `base`, below the range the system hands out for
    /// port 0, so tests running side by side never draw the same one.
    fn free_pair(base: u16) -> (u16, u16) {
        let a = ports::free_from(base).unwrap();
        (a, ports::free_from(a + 1).unwrap())
    }

    #[tokio::test]
    async fn the_proxies_move_to_new_ports_while_running() {
        let _serial = crate::net::serial();
        let (socks, http) = free_pair(43_100);
        let front = Front::bind(socks, http).await.unwrap();
        assert!(!ports::is_free(socks));
        let (new_socks, new_http) = free_pair(43_200);
        front.rebind(new_socks, new_http).await.unwrap();
        assert_eq!(front.ports(), (new_socks, new_http));
        assert!(ports::await_released(socks, std::time::Duration::from_secs(1)).await);
        assert!(ports::await_released(http, std::time::Duration::from_secs(1)).await);
        assert!(!ports::is_free(new_socks) && !ports::is_free(new_http));
    }

    #[tokio::test]
    async fn a_taken_port_leaves_the_old_ones_in_place() {
        let _serial = crate::net::serial();
        let (socks, http) = free_pair(43_300);
        let front = Front::bind(socks, http).await.unwrap();
        let holder = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let taken = holder.local_addr().unwrap().port();
        assert!(front.rebind(taken, free_pair(43_400).1).await.is_err());
        assert_eq!(front.ports(), (socks, http));
        assert!(!ports::is_free(socks));
    }
}
