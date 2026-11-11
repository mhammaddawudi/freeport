//! Reads an HTTP proxy request far enough to know where it goes.

use anyhow::{bail, Context, Result};
use tokio::io::AsyncReadExt;
use tokio::net::TcpStream;

const MAX_HEAD: usize = 32 * 1024;

#[derive(Debug, PartialEq)]
pub struct Request {
    pub host: String,
    pub port: u16,
    /// The head to send on for a plain request, rewritten to origin form;
    /// `None` for CONNECT, whose head is answered here.
    pub forward: Option<Vec<u8>>,
    /// Bytes read past the head, which belong to the body or the tunnel.
    pub rest: Vec<u8>,
}

pub async fn read_head(stream: &mut TcpStream) -> Result<Request> {
    let mut buffer = Vec::with_capacity(4096);
    let mut chunk = [0u8; 4096];
    loop {
        let n = stream.read(&mut chunk).await?;
        if n == 0 {
            bail!("the client closed before sending a request");
        }
        buffer.extend_from_slice(&chunk[..n]);
        if let Some(end) = find_end(&buffer) {
            return parse(&buffer[..end], buffer[end..].to_vec());
        }
        if buffer.len() > MAX_HEAD {
            bail!("the request head is larger than {MAX_HEAD} bytes");
        }
    }
}

fn find_end(buffer: &[u8]) -> Option<usize> {
    buffer.windows(4).position(|w| w == b"\r\n\r\n").map(|at| at + 4)
}

pub fn parse(head: &[u8], rest: Vec<u8>) -> Result<Request> {
    let text = std::str::from_utf8(head).context("the request head is not text")?;
    let mut lines = text.split("\r\n");
    let first = lines.next().unwrap_or_default();
    let mut parts = first.split_whitespace();
    let (Some(method), Some(target), Some(version)) = (parts.next(), parts.next(), parts.next()) else {
        bail!("'{first}' is not a request line");
    };

    if method.eq_ignore_ascii_case("CONNECT") {
        let (host, port) = split_host_port(target, 443)?;
        return Ok(Request { host, port, forward: None, rest });
    }

    let Some(after_scheme) = target.strip_prefix("http://") else {
        bail!("only absolute http:// requests and CONNECT can be proxied, got '{target}'");
    };
    let (authority, path) = match after_scheme.find('/') {
        Some(at) => (&after_scheme[..at], &after_scheme[at..]),
        None => (after_scheme, "/"),
    };
    let (host, port) = split_host_port(authority, 80)?;

    // One upstream connection per client connection: ask for it to close
    // rather than let keep-alive carry a request for another host down it.
    let mut forward = format!("{method} {path} {version}\r\n");
    for line in lines.filter(|line| !line.is_empty()) {
        let name = line.split(':').next().unwrap_or_default().trim().to_ascii_lowercase();
        if name != "proxy-connection" && name != "connection" && name != "keep-alive" {
            forward.push_str(line);
            forward.push_str("\r\n");
        }
    }
    forward.push_str("Connection: close\r\n\r\n");
    Ok(Request { host, port, forward: Some(forward.into_bytes()), rest })
}

fn split_host_port(authority: &str, default_port: u16) -> Result<(String, u16)> {
    let authority = authority.rsplit('@').next().unwrap_or(authority);
    if let Some(inner) = authority.strip_prefix('[') {
        let (host, tail) = inner.split_once(']').context("an IPv6 address is missing its ']'")?;
        let port = match tail.strip_prefix(':') {
            Some(port) => port.parse().context("the port is not a number")?,
            None => default_port,
        };
        return Ok((host.to_string(), port));
    }
    match authority.rsplit_once(':') {
        Some((host, port)) => Ok((host.to_string(), port.parse().context("the port is not a number")?)),
        None => Ok((authority.to_string(), default_port)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connect_keeps_the_rest_for_the_tunnel() {
        let request = parse(b"CONNECT claude.ai:443 HTTP/1.1\r\nHost: claude.ai:443\r\n\r\n", b"\x16\x03".to_vec()).unwrap();
        assert_eq!(request.host, "claude.ai");
        assert_eq!(request.port, 443);
        assert!(request.forward.is_none());
        assert_eq!(request.rest, b"\x16\x03");
    }

    #[test]
    fn a_plain_request_is_rewritten_to_origin_form() {
        let head = b"GET http://example.com:8080/a?b=1 HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n\r\n";
        let request = parse(head, Vec::new()).unwrap();
        assert_eq!((request.host.as_str(), request.port), ("example.com", 8080));
        let forward = String::from_utf8(request.forward.unwrap()).unwrap();
        assert!(forward.starts_with("GET /a?b=1 HTTP/1.1\r\nHost: example.com\r\n"));
        assert!(!forward.to_lowercase().contains("proxy-connection"));
        assert!(forward.ends_with("Connection: close\r\n\r\n"));
    }

    #[test]
    fn ipv6_authorities_keep_their_brackets_off() {
        assert_eq!(split_host_port("[2606:4700::1]:443", 80).unwrap(), ("2606:4700::1".to_string(), 443));
        assert_eq!(split_host_port("example.com", 80).unwrap(), ("example.com".to_string(), 80));
    }

    #[test]
    fn https_targets_without_connect_are_refused() {
        assert!(parse(b"GET https://x/ HTTP/1.1\r\n\r\n", Vec::new()).is_err());
    }
}
