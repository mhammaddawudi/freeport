//! Local ports: finding a free one, waiting for a tunnel's proxy to carry
//! traffic, and for a listener to go.

use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::time::Duration;

use tokio::net::TcpStream;
use tokio::time::{sleep, timeout, Instant};

use super::socks;

const SEARCH_SPAN: u16 = 40;

/// The first port from `start` nothing listens on.
///
/// Cancelling the core does not always release its listener at once, so a
/// new attempt moves on instead of waiting for a port that may stay taken.
pub fn free_from(start: u16) -> Option<u16> {
    (start..start.saturating_add(SEARCH_SPAN)).find(|port| is_free(*port))
}

pub fn is_free(port: u16) -> bool {
    TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, port))).is_ok()
}

/// Where readiness probes ask the tunnel to connect. Only the CONNECT is
/// made, so nothing is sent to it.
const PROBE_HOST: &str = "www.cloudflare.com";
const PROBE_EVERY: Duration = Duration::from_millis(500);
const PROBE_TIMEOUT: Duration = Duration::from_secs(8);

/// Whether the SOCKS5 proxy on `port` can open a connection through the
/// tunnel right now.
pub async fn carries(port: u16) -> bool {
    let attempt = async {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).await?;
        socks::connect(&mut stream, PROBE_HOST, 443).await
    };
    matches!(timeout(PROBE_TIMEOUT, attempt).await, Ok(Ok(())))
}

/// Waits until the proxy on `port` carries a connection, or `within` runs
/// out. The core binds its port before the tunnel is up and holds it for the
/// whole run, so a listener alone says nothing about the tunnel.
pub async fn await_carrying(port: u16, within: Duration) -> bool {
    let deadline = Instant::now() + within;
    while Instant::now() < deadline {
        if timeout(deadline - Instant::now(), carries(port)).await.unwrap_or(false) {
            return true;
        }
        sleep(PROBE_EVERY).await;
    }
    false
}

/// Waits until `port` can be bound again, or `within` runs out.
pub async fn await_released(port: u16, within: Duration) -> bool {
    let deadline = Instant::now() + within;
    while Instant::now() < deadline {
        if is_free(port) {
            return true;
        }
        sleep(Duration::from_millis(150)).await;
    }
    is_free(port)
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::*;

    /// A SOCKS5 proxy that grants every CONNECT, or one that holds its port
    /// and closes every connection, the way the core does before its tunnel
    /// is up.
    async fn proxy(grants: bool) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut client, _)) = listener.accept().await {
                if !grants {
                    continue;
                }
                let mut greeting = [0u8; 3];
                client.read_exact(&mut greeting).await.unwrap();
                client.write_all(&[0x05, 0x00]).await.unwrap();
                let mut head = [0u8; 5];
                client.read_exact(&mut head).await.unwrap();
                let mut rest = vec![0u8; head[4] as usize + 2];
                client.read_exact(&mut rest).await.unwrap();
                client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await.unwrap();
            }
        });
        port
    }

    #[tokio::test]
    async fn a_proxy_carries_only_once_it_grants_a_connect() {
        assert!(carries(proxy(true).await).await);
        let holding = proxy(false).await;
        assert!(!carries(holding).await);
        assert!(!await_carrying(holding, Duration::from_secs(1)).await);
    }
}
