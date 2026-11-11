//! What the outside world sees of the session: country, latency, and whether
//! Claude will serve it.

use std::time::{Duration, Instant};

use anyhow::{Context, Result};

const TRACE_URL: &str = "https://www.cloudflare.com/cdn-cgi/trace";
const CLAUDE_PROBE_URL: &str = "https://api.anthropic.com/v1/models";
const TIMEOUT: Duration = Duration::from_secs(12);

#[derive(Debug, Clone, PartialEq)]
pub struct Trace {
    /// Two-letter country Cloudflare places the exit in.
    pub country: String,
    pub colo: String,
    pub ip: String,
    pub warp: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClaudeReach {
    /// The API answered as it does for a supported country.
    Open,
    /// The API refused the request outright, as it does for a refused region.
    Refused,
}

fn client(socks_port: u16) -> Result<reqwest::Client> {
    let proxy = reqwest::Proxy::all(format!("socks5h://127.0.0.1:{socks_port}")).context("bad proxy address")?;
    reqwest::Client::builder()
        .proxy(proxy)
        .timeout(TIMEOUT)
        .user_agent("Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) Freeport")
        .build()
        .context("could not build an http client")
}

pub async fn trace(socks_port: u16) -> Result<Trace> {
    let body = client(socks_port)?.get(TRACE_URL).send().await?.error_for_status()?.text().await?;
    parse_trace(&body).context("the trace held no country")
}

pub fn parse_trace(body: &str) -> Option<Trace> {
    let field = |name: &str| {
        body.lines()
            .find_map(|line| line.strip_prefix(name).and_then(|rest| rest.strip_prefix('=')))
            .map(|value| value.trim().to_string())
    };
    let country = field("loc")?.to_uppercase();
    (country.len() == 2).then(|| Trace {
        country,
        colo: field("colo").unwrap_or_default(),
        ip: field("ip").unwrap_or_default(),
        warp: field("warp").is_some_and(|value| value != "off"),
    })
}

/// Asks the Claude API, without a key, whether it would serve this exit.
///
/// A supported country gets 401 (no key); a refused one gets 403.
pub async fn claude(socks_port: u16) -> Result<ClaudeReach> {
    let status = client(socks_port)?
        .get(CLAUDE_PROBE_URL)
        .header("anthropic-version", "2023-06-01")
        .send()
        .await?
        .status();
    Ok(match status.as_u16() {
        403 | 451 => ClaudeReach::Refused,
        _ => ClaudeReach::Open,
    })
}

/// Time for a full HTTPS request through the session, in milliseconds.
pub async fn latency(socks_port: u16) -> Result<u64> {
    let client = client(socks_port)?;
    let started = Instant::now();
    client.head(TRACE_URL).send().await?;
    Ok(started.elapsed().as_millis() as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_trace_is_read_line_by_line() {
        let body = "fl=1\nip=1.2.3.4\ncolo=FRA\nloc=de\nwarp=on\n";
        let trace = parse_trace(body).unwrap();
        assert_eq!(trace.country, "DE");
        assert_eq!(trace.colo, "FRA");
        assert!(trace.warp);
    }

    #[test]
    fn a_trace_without_a_country_is_none() {
        assert!(parse_trace("ip=1.2.3.4\n").is_none());
    }
}
