//! Region unlock: a second exit, outside Cloudflare.
//!
//! Cloudflare places every WARP egress in the country the client connects
//! from, however many WARP hops are nested, so from an Iranian line every
//! protocol traces as `loc=IR` and Claude, ChatGPT and the rest refuse it.
//! Psiphon dialled *through* the tunnel leaves from one of its own servers
//! abroad instead. Its console client is built into this binary and runs as
//! a child process; its notices arrive as JSON lines.

use std::fs;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use parking_lot::Mutex;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncRead, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::watch;

use crate::settings;

/// Psiphon's console client for this platform; build.rs finds it in assets/.
static CLIENT: &[u8] = include_bytes!(env!("FREEPORT_PSIPHON"));

/// Its file name once written out.
const CLIENT_NAME: &str = if cfg!(windows) { "psiphon.exe" } else { "psiphon" };

/// base64 of Psiphon's public remote server list address.
const SERVER_LIST_URL_B64: &str =
    "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tLy9wc2lwaG9uL3dlYi9tanI0LXAyM3ItcHV3bC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk";

/// Psiphon's key for that list; the client refuses an unsigned one.
const SERVER_LIST_KEY: &str = concat!(
    "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH",
    "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5",
    "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot",
    "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6",
    "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G",
    "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O",
    "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8",
    "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz",
    "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal",
    "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM=",
);

/// Through a SOCKS proxy only TCP protocols can be dialled.
const TCP_PROTOCOLS: [&str; 9] = [
    "SSH",
    "OSSH",
    "TLS-OSSH",
    "UNFRONTED-MEEK-OSSH",
    "UNFRONTED-MEEK-HTTPS-OSSH",
    "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
    "SHADOWSOCKS-OSSH",
    "FRONTED-MEEK-OSSH",
    "FRONTED-MEEK-HTTP-OSSH",
];

#[derive(Debug, Clone, PartialEq)]
enum Readiness {
    Waiting,
    Up,
    /// Was up, and Psiphon reports no tunnel now; it is looking for another.
    Lost,
    Failed(String),
}

/// What a running relay says about itself.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Health {
    Up,
    /// Psiphon is running but has no tunnel to carry traffic on.
    Searching,
    Stopped,
}

#[derive(Default)]
pub struct Relay {
    child: Mutex<Option<Child>>,
    readiness: Mutex<Option<watch::Receiver<Readiness>>>,
    /// The country the running Psiphon was asked for; empty for its own pick.
    country: Mutex<String>,
    /// Set while [`Relay::start`] runs, so a health check does not start a
    /// second Psiphon over the one coming up.
    starting: AtomicBool,
}

/// Clears [`Relay::starting`] however a start ends.
struct Starting<'a>(&'a AtomicBool);

impl Drop for Starting<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

impl Relay {
    /// Starts Psiphon dialling out through `upstream_port` (0: directly) and
    /// serving SOCKS5 on `local_port`, and waits for its first tunnel.
    pub async fn start(&self, upstream_port: u16, local_port: u16, country: &str, within: Duration) -> Result<()> {
        self.starting.store(true, Ordering::SeqCst);
        let _starting = Starting(&self.starting);
        self.stop().await;
        let binary = install()?;
        let data = settings::sub_dir("psiphon")?;
        let config = data.join("config.json");
        reap_stale(&data, &config).await;
        fs::write(&config, config_json(upstream_port, local_port, country, &data).to_string())
            .context("could not write the psiphon config")?;

        let mut command = Command::new(&binary);
        #[cfg(windows)]
        command.creation_flags(0x0800_0000);
        let mut child = command
            .arg("-config")
            .arg(&config)
            .current_dir(&data)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .context("psiphon did not start")?;
        if let Some(pid) = child.id() {
            let _ = fs::write(data.join(PID_FILE), pid.to_string());
        }
        let towards = if country.is_empty() { String::new() } else { format!(" towards {country}") };
        let role = if upstream_port == 0 { "psiphon" } else { "region unlock" };
        log::info!(target: "freeport", "[*] {role}: starting psiphon{towards}");

        let (ready_tx, mut ready_rx) = watch::channel(Readiness::Waiting);
        let ready_tx = Arc::new(ready_tx);
        if let Some(out) = child.stdout.take() {
            tokio::spawn(read_notices(out, ready_tx.clone()));
        }
        if let Some(err) = child.stderr.take() {
            tokio::spawn(read_notices(err, ready_tx.clone()));
        }
        *self.child.lock() = Some(child);
        *self.readiness.lock() = Some(ready_rx.clone());
        *self.country.lock() = country.to_string();

        let outcome = tokio::time::timeout(within, async {
            loop {
                if ready_rx.changed().await.is_err() {
                    return Readiness::Failed("psiphon exited".into());
                }
                let state = ready_rx.borrow().clone();
                if state != Readiness::Waiting {
                    return state;
                }
            }
        })
        .await
        .unwrap_or_else(|_| Readiness::Failed(format!("psiphon found no server within {}s", within.as_secs())));

        let why = match outcome {
            Readiness::Up => return Ok(()),
            Readiness::Failed(why) => why,
            Readiness::Waiting | Readiness::Lost => "psiphon stopped before it was ready".to_string(),
        };
        self.stop().await;
        bail!(why)
    }

    pub async fn stop(&self) {
        let child = self.child.lock().take();
        *self.readiness.lock() = None;
        if let Some(mut child) = child {
            let _ = child.start_kill();
            let _ = tokio::time::timeout(Duration::from_secs(2), child.wait()).await;
            if let Ok(data) = settings::sub_dir("psiphon") {
                let _ = fs::remove_file(data.join(PID_FILE));
            }
        }
    }

    pub fn is_alive(&self) -> bool {
        match self.child.lock().as_mut() {
            Some(child) => matches!(child.try_wait(), Ok(None)),
            None => false,
        }
    }

    pub fn health(&self) -> Health {
        if !self.is_alive() {
            return Health::Stopped;
        }
        let state = self.readiness.lock().as_ref().map(|rx| rx.borrow().clone());
        match state {
            Some(Readiness::Up) => Health::Up,
            Some(Readiness::Waiting | Readiness::Lost) => Health::Searching,
            Some(Readiness::Failed(_)) | None => Health::Stopped,
        }
    }

    pub fn is_starting(&self) -> bool {
        self.starting.load(Ordering::SeqCst)
    }

    pub fn country(&self) -> String {
        self.country.lock().clone()
    }
}

async fn read_notices<R: AsyncRead + Unpin>(stream: R, ready: Arc<watch::Sender<Readiness>>) {
    let mut lines = BufReader::new(stream).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let Ok(notice) = serde_json::from_str::<Value>(&line) else { continue };
        let data = &notice["data"];
        match notice["noticeType"].as_str().unwrap_or_default() {
            "Tunnels" => {
                let count = data["count"].as_i64().unwrap_or(0);
                let was = ready.borrow().clone();
                if count > 0 && was == Readiness::Waiting {
                    log::info!(target: "freeport", "[+] region unlock: psiphon tunnel is up");
                    let _ = ready.send(Readiness::Up);
                } else if count > 0 && was == Readiness::Lost {
                    log::info!(target: "freeport", "[+] region unlock: psiphon found another tunnel");
                    let _ = ready.send(Readiness::Up);
                } else if count == 0 && was == Readiness::Up {
                    log::warn!(target: "freeport", "[-] region unlock: psiphon lost its tunnel, looking for another");
                    let _ = ready.send(Readiness::Lost);
                }
            }
            "ActiveTunnel" => {
                let protocol = data["protocol"].as_str().unwrap_or_default();
                let region = data["region"].as_str().unwrap_or_default();
                log::info!(target: "freeport", "[*] psiphon via {protocol} {region}");
            }
            "Error" => {
                let message: String = data["message"].as_str().unwrap_or_default().chars().take(240).collect();
                log::debug!(target: "freeport", "[-] psiphon: {message}");
            }
            _ => {}
        }
    }
    ready.send_if_modified(|state| match state {
        Readiness::Waiting | Readiness::Lost => {
            *state = Readiness::Failed("psiphon exited".into());
            true
        }
        _ => false,
    });
}

fn config_json(upstream_port: u16, local_port: u16, country: &str, data: &std::path::Path) -> Value {
    let mut config = json!({
        "PropagationChannelId": "FFFFFFFFFFFFFFFF",
        "SponsorId": "FFFFFFFFFFFFFFFF",
        "ClientPlatform": if cfg!(windows) { "Windows_freeport" } else { "MacOS_freeport" },
        "ClientVersion": "1",
        "RemoteServerListURLs": [{ "URL": SERVER_LIST_URL_B64 }],
        "RemoteServerListSignaturePublicKey": SERVER_LIST_KEY,
        "LimitTunnelProtocols": TCP_PROTOCOLS,
        "InproxyTunnelProtocolSelectionProbability": 0.0,
        "DataRootDirectory": data.to_string_lossy(),
        "LocalSocksProxyPort": local_port,
        "DisableLocalHTTPProxy": true,
        "UpstreamProxyURL": format!("socks5://127.0.0.1:{upstream_port}"),
        "EmitBytesTransferred": false,
    });
    // Port 0 is Psiphon on its own: straight out, on every protocol it has.
    if upstream_port == 0 {
        if let Some(map) = config.as_object_mut() {
            map.remove("UpstreamProxyURL");
            map.remove("LimitTunnelProtocols");
            map.remove("InproxyTunnelProtocolSelectionProbability");
        }
    }
    if !country.is_empty() {
        config["EgressRegion"] = json!(country);
    }
    config
}

/// Holds the pid of the Psiphon this Freeport started, so the next start can
/// stop it if Freeport went away without stopping it first (a crash, or
/// `kill -9`). A Psiphon left holding the data folder makes every new one
/// exit at once.
const PID_FILE: &str = "psiphon.pid";

async fn reap_stale(data: &std::path::Path, config: &std::path::Path) {
    let Some(pid) = fs::read_to_string(data.join(PID_FILE)).ok().and_then(|text| text.trim().parse::<i32>().ok()) else {
        return;
    };
    let _ = fs::remove_file(data.join(PID_FILE));
    if !is_our_psiphon(pid, config) {
        return;
    }
    log::info!(target: "freeport", "[*] stopping a psiphon left over from an earlier run (pid {pid})");
    terminate(pid, false);
    for _ in 0..20 {
        tokio::time::sleep(Duration::from_millis(100)).await;
        if !is_our_psiphon(pid, config) {
            return;
        }
    }
    // It did not stop when asked.
    terminate(pid, true);
}

#[cfg(unix)]
fn terminate(pid: i32, force: bool) {
    // SAFETY: a plain signal to a process checked to be our Psiphon.
    unsafe { libc::kill(pid, if force { libc::SIGKILL } else { libc::SIGTERM }) };
}

#[cfg(windows)]
fn terminate(pid: i32, _force: bool) {
    let _ = hidden(std::process::Command::new("taskkill").args(["/PID", &pid.to_string(), "/T", "/F"])).output();
}

/// Keeps a console window from flashing up for a child process on Windows.
#[cfg(windows)]
fn hidden(command: &mut std::process::Command) -> &mut std::process::Command {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW)
}

/// Whether `pid` is still a Psiphon running with this exact config, so a pid
/// the system has since handed to something else is never signalled.
#[cfg(unix)]
fn is_our_psiphon(pid: i32, config: &std::path::Path) -> bool {
    let Ok(out) = std::process::Command::new("/bin/ps").args(["-p", &pid.to_string(), "-o", "command="]).output() else {
        return false;
    };
    let command = String::from_utf8_lossy(&out.stdout);
    command.contains("psiphon") && command.contains(config.to_string_lossy().as_ref())
}

/// Windows shows no command lines to `tasklist`, so the image name has to do.
#[cfg(windows)]
fn is_our_psiphon(pid: i32, _config: &std::path::Path) -> bool {
    let filter = format!("PID eq {pid}");
    let Ok(out) = hidden(std::process::Command::new("tasklist").args(["/FI", &filter, "/NH"])).output() else {
        return false;
    };
    String::from_utf8_lossy(&out.stdout).to_lowercase().contains(CLIENT_NAME)
}

/// Writes the built-in client out once per build, executable.
fn install() -> Result<PathBuf> {
    let path = settings::sub_dir("bin")?.join(CLIENT_NAME);
    let current = fs::metadata(&path).map(|meta| meta.len() as usize == CLIENT.len()).unwrap_or(false);
    if !current {
        let staging = path.with_extension("new");
        fs::write(&staging, CLIENT).context("could not write the psiphon client")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&staging, fs::Permissions::from_mode(0o755))?;
        }
        // Windows will not rename over a file; the old copy goes first.
        let _ = fs::remove_file(&path);
        fs::rename(&staging, &path).context("could not install the psiphon client")?;
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_config_dials_through_the_tunnel_and_names_the_country() {
        let config = config_json(18190, 18290, "DE", std::path::Path::new("/tmp/x"));
        assert_eq!(config["UpstreamProxyURL"], "socks5://127.0.0.1:18190");
        assert_eq!(config["LocalSocksProxyPort"], 18290);
        assert_eq!(config["EgressRegion"], "DE");
        assert!(config_json(1, 2, "", std::path::Path::new("/")).get("EgressRegion").is_none());
    }

    fn tunnels(count: u32) -> String {
        format!("{{\"noticeType\":\"Tunnels\",\"data\":{{\"count\":{count}}}}}\n")
    }

    #[tokio::test]
    async fn notices_track_the_tunnel_being_lost_and_found() {
        let (mut writer, reader) = tokio::io::duplex(4096);
        let (tx, mut rx) = watch::channel(Readiness::Waiting);
        let reading = tokio::spawn(read_notices(reader, Arc::new(tx)));
        use tokio::io::AsyncWriteExt;
        for (count, expected) in [(1, Readiness::Up), (0, Readiness::Lost), (2, Readiness::Up)] {
            writer.write_all(tunnels(count).as_bytes()).await.unwrap();
            rx.changed().await.unwrap();
            assert_eq!(*rx.borrow_and_update(), expected);
        }
        // Psiphon exiting while it searches is a failure, not a quiet stop.
        writer.write_all(tunnels(0).as_bytes()).await.unwrap();
        rx.changed().await.unwrap();
        drop(writer);
        reading.await.unwrap();
        assert!(matches!(*rx.borrow(), Readiness::Failed(_)));
    }

    #[test]
    fn psiphon_on_its_own_dials_straight_out() {
        let config = config_json(0, 18191, "", std::path::Path::new("/tmp/psiphon"));
        assert!(config.get("UpstreamProxyURL").is_none());
        assert!(config.get("LimitTunnelProtocols").is_none());
        assert_eq!(config["LocalSocksProxyPort"], 18191);
    }

    #[test]
    fn the_client_is_built_in() {
        assert!(CLIENT.len() > 1_000_000);
        let magic: &[u8] = if cfg!(windows) { b"MZ" } else if cfg!(target_os = "macos") { &[0xcf, 0xfa, 0xed, 0xfe] } else { b"\x7fELF" };
        assert_eq!(&CLIENT[..magic.len()], magic);
    }
}
