//! Lets devices on the same Wi-Fi use Freeport: SOCKS5 and HTTP listeners on
//! every interface, each relayed byte for byte to the local front proxy, which
//! already speaks both.

use std::net::{IpAddr, Ipv4Addr, UdpSocket};

use anyhow::{Context, Result};
use tokio::net::{TcpListener, TcpStream};
use tokio::task::JoinHandle;

pub const SOCKS_PORT: u16 = 10_808;
pub const HTTP_PORT: u16 = 10_809;

/// The two listeners; dropping it closes them.
pub struct LanShare {
    tasks: Vec<JoinHandle<()>>,
    front: (u16, u16),
}

impl LanShare {
    /// Serves the LAN, relaying to the front's SOCKS and HTTP ports.
    pub async fn start(front_socks: u16, front_http: u16) -> Result<LanShare> {
        let mut tasks = Vec::new();
        for (port, target) in [(SOCKS_PORT, front_socks), (HTTP_PORT, front_http)] {
            let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, port))
                .await
                .with_context(|| format!("port {port} is taken"))?;
            tasks.push(tokio::spawn(relay(listener, target)));
        }
        log::info!(
            target: "freeport",
            "[+] Wi-Fi sharing on {}:{SOCKS_PORT} (SOCKS5), :{HTTP_PORT} (HTTP)",
            address().map(|ip| ip.to_string()).unwrap_or_else(|| "?".into())
        );
        Ok(LanShare { tasks, front: (front_socks, front_http) })
    }

    pub fn front(&self) -> (u16, u16) {
        self.front
    }
}

impl Drop for LanShare {
    fn drop(&mut self) {
        self.tasks.iter().for_each(JoinHandle::abort);
    }
}

async fn relay(listener: TcpListener, target: u16) {
    while let Ok((mut client, _)) = listener.accept().await {
        tokio::spawn(async move {
            if let Ok(mut upstream) = TcpStream::connect(("127.0.0.1", target)).await {
                let _ = client.set_nodelay(true);
                let _ = tokio::io::copy_bidirectional(&mut client, &mut upstream).await;
            }
        });
    }
}

/// This computer's private IPv4 on the LAN, the one other devices type in.
/// Read from the route the system would use; nothing is sent.
pub fn address() -> Option<IpAddr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    ["192.168.1.1:9", "10.0.0.1:9", "172.16.0.1:9", "1.1.1.1:9"].iter().find_map(|probe| {
        socket.connect(probe).ok()?;
        let ip = socket.local_addr().ok()?.ip();
        match ip {
            // TUN mode's own interface is not reachable from the LAN.
            IpAddr::V4(v4) if v4.is_private() && v4.octets()[..3] != [172, 19, 0] => Some(ip),
            _ => None,
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[tokio::test]
    async fn bytes_reach_the_front_and_come_back() {
        let _serial = crate::net::serial();
        let echo = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let echo_port = echo.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut s, _) = echo.accept().await.unwrap();
            let mut buf = [0u8; 5];
            s.read_exact(&mut buf).await.unwrap();
            s.write_all(&buf).await.unwrap();
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(relay(listener, echo_port));
        let mut client = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        client.write_all(b"hello").await.unwrap();
        let mut back = [0u8; 5];
        client.read_exact(&mut back).await.unwrap();
        assert_eq!(&back, b"hello");
    }
}
