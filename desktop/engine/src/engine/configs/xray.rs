//! Xray as a child process: one SOCKS inbound per config, each routed to
//! its own outbound, so a whole batch is tested by one process.

use std::fs;
use std::path::PathBuf;
use std::process::Stdio;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use serde_json::json;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};

use super::link::ShareLink;
use crate::settings;

static XRAY: &[u8] = include_bytes!(env!("FREEPORT_XRAY"));
const EXT: &str = if cfg!(windows) { ".exe" } else { "" };
const STARTUP: Duration = Duration::from_secs(8);

pub fn is_available() -> bool {
    !XRAY.is_empty()
}

/// A config served on a local SOCKS port.
#[derive(Clone)]
pub struct Route {
    pub link: ShareLink,
    pub port: u16,
}

/// A running Xray; stopped when dropped.
pub struct Running {
    child: Child,
}

impl Running {
    pub fn is_alive(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }
}

impl Drop for Running {
    fn drop(&mut self) {
        let _ = self.child.start_kill();
    }
}

/// The folder Xray and its configurations live in.
pub fn folder() -> Result<PathBuf> {
    settings::sub_dir("configs")
}

fn install() -> Result<PathBuf> {
    let path = folder()?.join(format!("xray{EXT}"));
    let current = fs::metadata(&path).map(|m| m.len() as usize == XRAY.len()).unwrap_or(false);
    if !current {
        let staging = path.with_extension("new");
        fs::write(&staging, XRAY).context("could not write xray")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&staging, fs::Permissions::from_mode(0o755))?;
        }
        let _ = fs::remove_file(&path);
        fs::rename(&staging, &path).context("could not install xray")?;
    }
    Ok(path)
}

/// Starts Xray carrying `routes`; returns once it says it started.
pub async fn start(routes: &[Route], name: &str, udp: bool) -> Result<Running> {
    if !is_available() {
        bail!("xray is not in this build");
    }
    let binary = install()?;
    let dir = folder()?;
    let inbounds: Vec<_> = routes
        .iter()
        .enumerate()
        .map(|(i, r)| json!({ "tag": format!("in{i}"), "listen": "127.0.0.1", "port": r.port, "protocol": "socks", "settings": { "udp": udp } }))
        .collect();
    let outbounds: Vec<_> = routes.iter().enumerate().map(|(i, r)| r.link.outbound(&format!("out{i}"))).collect();
    let rules: Vec<_> = (0..routes.len()).map(|i| json!({ "type": "field", "inboundTag": [format!("in{i}")], "outboundTag": format!("out{i}") })).collect();
    let config = json!({ "log": { "loglevel": "warning" }, "inbounds": inbounds, "outbounds": outbounds, "routing": { "rules": rules } });
    let path = dir.join(format!("{name}.json"));
    fs::write(&path, serde_json::to_vec(&config)?).context("could not write the xray config")?;

    let mut command = Command::new(&binary);
    #[cfg(windows)]
    command.creation_flags(0x0800_0000);
    let mut child = command
        .args(["run", "-c"])
        .arg(&path)
        .current_dir(&dir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .context("xray did not start")?;
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let mut running = Running { child };

    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
    for stream in [stdout.map(|s| Box::new(s) as Box<dyn tokio::io::AsyncRead + Unpin + Send>), stderr.map(|s| Box::new(s) as _)].into_iter().flatten() {
        let tx = tx.clone();
        tokio::spawn(async move {
            let mut lines = BufReader::new(stream).lines();
            while let Ok(Some(line)) = lines.next_line().await {
                let _ = tx.send(line);
            }
        });
    }
    drop(tx);
    let mut last = String::new();
    let started = tokio::time::timeout(STARTUP, async {
        while let Some(line) = rx.recv().await {
            if line.contains("started") && line.contains("Xray") {
                return true;
            }
            last = line;
        }
        false
    })
    .await;
    match started {
        Ok(true) => {
            // Keeps reading so the pipes never fill.
            tokio::spawn(async move { while rx.recv().await.is_some() {} });
            Ok(running)
        }
        _ if !running.is_alive() => bail!("xray refused the configuration: {}", last.trim()),
        _ => bail!("xray did not start in time"),
    }
}
