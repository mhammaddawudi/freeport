//! The client half of SOCKS5, just enough to open a CONNECT by name.

use anyhow::{bail, Context, Result};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

/// Asks the proxy on `stream` for a connection to `host:port`. The name is
/// sent as is, so it resolves at the exit rather than on this Mac.
pub async fn connect(stream: &mut TcpStream, host: &str, port: u16) -> Result<()> {
    if host.is_empty() || host.len() > 255 {
        bail!("'{host}' is not a host name a proxy can carry");
    }
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await.context("the proxy closed during the handshake")?;
    if greeting != [0x05, 0x00] {
        bail!("the proxy wants authentication");
    }

    let mut request = Vec::with_capacity(7 + host.len());
    request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host.len() as u8]);
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&request).await?;

    let mut reply = [0u8; 4];
    stream.read_exact(&mut reply).await.context("the proxy closed before replying")?;
    if reply[1] != 0x00 {
        bail!("the proxy could not reach {host}:{port} ({})", reason(reply[1]));
    }
    let skip = match reply[3] {
        0x01 => 4 + 2,
        0x04 => 16 + 2,
        0x03 => stream.read_u8().await? as usize + 2,
        other => bail!("the proxy answered with address type {other}"),
    };
    let mut bound = vec![0u8; skip];
    stream.read_exact(&mut bound).await?;
    Ok(())
}

fn reason(code: u8) -> &'static str {
    match code {
        0x01 => "general failure",
        0x02 => "not allowed",
        0x03 => "network unreachable",
        0x04 => "host unreachable",
        0x05 => "connection refused",
        0x06 => "ttl expired",
        0x07 => "command not supported",
        0x08 => "address type not supported",
        _ => "unknown error",
    }
}
