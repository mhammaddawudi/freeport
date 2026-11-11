//! Tor through Snowflake, the way the Android app runs it: Arti as a child
//! process with Snowflake as its bridge transport, so there is no bridge
//! address for a censor to list. Slow, and the last resort.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use parking_lot::Mutex;
use tokio::io::{AsyncBufReadExt, AsyncRead, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::watch;

use crate::settings;

static ARTI: &[u8] = include_bytes!(env!("FREEPORT_ARTI"));
static SNOWFLAKE: &[u8] = include_bytes!(env!("FREEPORT_SNOWFLAKE"));
const EXT: &str = if cfg!(windows) { ".exe" } else { "" };

/// Snowflake's own bridge lines, as its client ships them (v2.15).
const ICE: &str = "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,\
stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,\
stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443";
const BRIDGES: [(&str, &str); 2] = [
    ("192.0.2.3:80", "2B280B23E1107BB62ABFC40DDCC8824814F80A72"),
    ("192.0.2.4:80", "8838024498816A039FCBBAB14E6F40A0843051FA"),
];

pub fn is_available() -> bool {
    !ARTI.is_empty() && !SNOWFLAKE.is_empty()
}

#[derive(Default)]
pub struct Tor {
    child: Mutex<Option<Child>>,
}

impl Tor {
    /// Starts Arti serving SOCKS5 on `port` and waits until Tor is usable.
    pub async fn start(&self, port: u16, within: Duration) -> Result<()> {
        self.stop().await;
        if !is_available() {
            bail!("tor is not in this build");
        }
        let data = settings::sub_dir("tor")?;
        let (arti, snowflake) = install()?;
        let config = data.join("arti.toml");
        fs::write(&config, config_toml(port, &data, &snowflake)).context("could not write the tor config")?;

        let mut command = Command::new(&arti);
        #[cfg(windows)]
        command.creation_flags(0x0800_0000);
        let mut child = command
            .args(["proxy", "-c"])
            .arg(&config)
            .current_dir(&data)
            .env("HOME", &data)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .context("tor did not start")?;
        log::info!(target: "freeport", "[*] tor: starting through snowflake");

        let (ready_tx, mut ready_rx) = watch::channel(false);
        let ready_tx = Arc::new(ready_tx);
        if let Some(out) = child.stdout.take() {
            tokio::spawn(read_log(out, ready_tx.clone()));
        }
        if let Some(err) = child.stderr.take() {
            tokio::spawn(read_log(err, ready_tx.clone()));
        }
        *self.child.lock() = Some(child);

        // Reduced to plain values at once: the watch guard must not live across an await.
        let ready = tokio::time::timeout(within, ready_rx.wait_for(|up| *up)).await.map(|seen| seen.is_ok());
        match ready {
            Ok(true) => {
                log::info!(target: "freeport", "[+] tor: connected through snowflake");
                Ok(())
            }
            Ok(false) => {
                self.stop().await;
                bail!("tor exited")
            }
            Err(_) => {
                self.stop().await;
                bail!("tor did not finish connecting within {}s", within.as_secs())
            }
        }
    }

    pub async fn stop(&self) {
        let child = self.child.lock().take();
        if let Some(mut child) = child {
            let _ = child.start_kill();
            let _ = tokio::time::timeout(Duration::from_secs(2), child.wait()).await;
        }
    }

    pub fn is_alive(&self) -> bool {
        match self.child.lock().as_mut() {
            Some(child) => matches!(child.try_wait(), Ok(None)),
            None => false,
        }
    }
}

async fn read_log<R: AsyncRead + Unpin>(stream: R, ready: Arc<watch::Sender<bool>>) {
    let mut lines = BufReader::new(stream).lines();
    while let Ok(Some(raw)) = lines.next_line().await {
        let line = strip_ansi(&raw);
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let lower = line.to_lowercase();
        if lower.contains("sufficiently bootstrapped") || lower.contains("socks now functional") {
            ready.send_replace(true);
        } else if lower.contains("bootstrap") {
            log::info!(target: "freeport", "[*] tor: {}", after(line, "INFO"));
        } else if line.contains(" WARN ") || line.contains(" ERROR ") {
            log::warn!(target: "freeport", "[-] tor: {}", after(after(line, " WARN "), " ERROR "));
        }
    }
}

fn after<'a>(line: &'a str, marker: &str) -> &'a str {
    let rest = line.split_once(marker).map(|(_, rest)| rest).unwrap_or(line).trim();
    &rest[..rest.len().min(200)]
}

fn strip_ansi(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if c == '\u{1b}' {
            for c in chars.by_ref() {
                if c.is_ascii_alphabetic() {
                    break;
                }
            }
        } else {
            out.push(c);
        }
    }
    out
}

fn quote(value: &str) -> String {
    format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
}

fn config_toml(port: u16, data: &Path, snowflake: &Path) -> String {
    let path = |sub: &str| quote(&data.join(sub).to_string_lossy());
    let bridges: Vec<String> = BRIDGES
        .iter()
        .map(|(addr, fp)| {
            quote(&format!(
                "snowflake {addr} {fp} fingerprint={fp} url=https://1098762253.rsc.cdn77.org/ \
                 fronts=www.cdn77.com,www.phpmyadmin.net {ICE} utls-imitate=hellorandomizedalpn"
            ))
        })
        .collect();
    format!(
        "[proxy]\nsocks_listen = \"127.0.0.1:{port}\"\n\n\
         [storage]\ncache_dir = {}\nstate_dir = {}\n\n\
         [storage.permissions]\ndangerously_trust_everyone = true\n\n\
         [logging]\nconsole = \"info\"\n\n\
         [bridges]\nenabled = true\nbridges = [\n  {}\n]\n\n\
         [[bridges.transports]]\nprotocols = [\"snowflake\"]\npath = {}\narguments = [\"-log\", {}]\nrun_on_startup = false\n",
        path("cache"),
        path("state"),
        bridges.join(",\n  "),
        quote(&snowflake.to_string_lossy()),
        path("snowflake.log"),
    )
}

/// Writes both programs out once per build, executable.
fn install() -> Result<(PathBuf, PathBuf)> {
    let bin = settings::sub_dir("bin")?;
    let arti = write_once(&bin.join(format!("arti{EXT}")), ARTI)?;
    let snowflake = write_once(&bin.join(format!("snowflake{EXT}")), SNOWFLAKE)?;
    Ok((arti, snowflake))
}

fn write_once(path: &Path, bytes: &[u8]) -> Result<PathBuf> {
    let current = fs::metadata(path).map(|meta| meta.len() as usize == bytes.len()).unwrap_or(false);
    if !current {
        let staging = path.with_extension("new");
        fs::write(&staging, bytes).with_context(|| format!("could not write {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&staging, fs::Permissions::from_mode(0o755))?;
        }
        let _ = fs::remove_file(path);
        fs::rename(&staging, path).with_context(|| format!("could not install {}", path.display()))?;
    }
    Ok(path.to_path_buf())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_config_listens_locally_and_uses_snowflake() {
        let text = config_toml(19050, Path::new("/tmp/tor"), Path::new("/tmp/bin/snowflake"));
        assert!(text.contains("socks_listen = \"127.0.0.1:19050\""));
        assert!(text.contains("protocols = [\"snowflake\"]"));
        assert!(text.contains("path = \"/tmp/bin/snowflake\""));
        assert_eq!(text.matches("snowflake 192.0.2.").count(), 2);
    }

    #[test]
    fn colour_codes_are_dropped_from_log_lines() {
        assert_eq!(strip_ansi("\u{1b}[32m INFO\u{1b}[0m ok"), " INFO ok");
    }
}
