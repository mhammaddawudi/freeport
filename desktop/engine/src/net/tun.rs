//! TUN mode: a virtual network interface that takes the whole system's
//! traffic, or only chosen apps', and hands it to Freeport's SOCKS port, for
//! programs that ignore proxy settings. sing-box runs the interface; creating
//! one needs administrator rights, so it starts behind the system's own
//! password prompt (macOS) or UAC (Windows), under a small watchdog that
//! stops it when Freeport asks or exits.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

use crate::settings;

static SINGBOX: &[u8] = include_bytes!(env!("FREEPORT_SINGBOX"));
const EXT: &str = if cfg!(windows) { ".exe" } else { "" };

pub fn is_available() -> bool {
    !SINGBOX.is_empty()
}

/// What goes through the interface.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Scope {
    /// Everything except Freeport itself and the local network.
    Everything,
    /// Only programs under these paths (an app bundle, or an .exe).
    Apps(Vec<String>),
}

/// sing-box's configuration: the interface, DNS answered through the tunnel,
/// Freeport's own traffic and the local network left direct.
pub fn config(socks_port: u16, scope: &Scope, own: &[String]) -> Value {
    let own_rule: Vec<String> = own.iter().map(|path| path_pattern(path)).collect();
    let proxied = match scope {
        Scope::Everything => None,
        Scope::Apps(paths) => Some(paths.iter().map(|path| path_pattern(path)).collect::<Vec<_>>()),
    };
    let mut rules = vec![
        json!({ "action": "sniff" }),
        json!({ "protocol": "dns", "action": "hijack-dns" }),
        json!({ "process_path_regex": own_rule, "outbound": "direct" }),
        json!({ "ip_is_private": true, "outbound": "direct" }),
        // QUIC would ride the SOCKS relay badly; refused, browsers fall back to TCP at once.
        json!({ "network": "udp", "port": 443, "action": "reject" }),
    ];
    let mut dns_rules = Vec::new();
    if let Some(apps) = &proxied {
        rules.push(json!({ "process_path_regex": apps, "outbound": "proxy" }));
        dns_rules.push(json!({ "process_path_regex": apps, "server": "remote" }));
    }
    let everything = proxied.is_none();
    json!({
        "log": { "level": "warn", "timestamp": true },
        "dns": {
            "servers": [
                { "type": "tcp", "tag": "remote", "server": "1.1.1.1", "detour": "proxy" },
                { "type": "local", "tag": "local" }
            ],
            "rules": dns_rules,
            "final": if everything { "remote" } else { "local" },
            "strategy": "ipv4_only"
        },
        "inbounds": [{
            "type": "tun",
            "tag": "tun-in",
            "address": ["172.19.0.1/30"],
            "mtu": 1500,
            "auto_route": true,
            "strict_route": true,
            "stack": "mixed"
        }],
        "outbounds": [
            { "type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": socks_port, "version": "5" },
            { "type": "direct", "tag": "direct" }
        ],
        "route": {
            "rules": rules,
            "final": if everything { "proxy" } else { "direct" },
            "auto_detect_interface": true,
            "find_process": true,
            "default_domain_resolver": "local"
        }
    })
}

/// A regular expression matching a program at `path` or anything inside it,
/// so an app bundle's helper processes count as the app.
fn path_pattern(path: &str) -> String {
    let mut out = String::from(if cfg!(windows) { "(?i)^" } else { "^" });
    for c in path.trim_end_matches(['/', '\\']).chars() {
        if "\\.+*?()|[]{}^$".contains(c) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}

/// The running interface, if Freeport started one.
#[derive(Default)]
pub struct Tun {
    running: parking_lot::Mutex<bool>,
}

impl Tun {
    /// Writes the configuration, asks for administrator rights and starts the
    /// interface; returns once it is up. Blocks while the prompt is open.
    pub fn start(&self, socks_port: u16, scope: &Scope) -> Result<()> {
        self.stop();
        if !is_available() {
            bail!("TUN mode is not in this build");
        }
        let dir = settings::sub_dir("tun")?;
        let binary = install(&dir)?;
        let config_path = dir.join("config.json");
        let own = own_paths();
        let text = serde_json::to_vec_pretty(&config(socks_port, scope, &own))?;
        fs::write(&config_path, text).context("could not write the TUN configuration")?;
        let check = std::process::Command::new(&binary).args(["check", "-c"]).arg(&config_path).output();
        if let Ok(out) = check {
            if !out.status.success() {
                bail!("the TUN configuration was refused: {}", String::from_utf8_lossy(&out.stderr).trim());
            }
        }
        let (stop, pid) = (dir.join("stop"), dir.join("pid"));
        let _ = fs::remove_file(&stop);
        let _ = fs::remove_file(&pid);
        elevate(&dir, &binary, &config_path, &stop, &pid)?;
        // The watchdog writes sing-box's pid once it runs; the interface follows a moment later.
        for _ in 0..40 {
            std::thread::sleep(Duration::from_millis(250));
            if let Some(id) = fs::read_to_string(&pid).ok().and_then(|text| text.trim().parse::<u32>().ok()) {
                std::thread::sleep(Duration::from_millis(1500));
                if !alive(id) {
                    bail!("the TUN interface stopped at once; see {}", dir.join("singbox.log").display());
                }
                *self.running.lock() = true;
                log::info!(target: "freeport", "[+] tun: the system's traffic now goes through Freeport");
                return Ok(());
            }
        }
        bail!("the TUN interface did not start (was the password prompt cancelled?)")
    }

    /// Asks the watchdog to stop the interface; it needs no new prompt.
    pub fn stop(&self) {
        let was = std::mem::replace(&mut *self.running.lock(), false);
        let Ok(dir) = settings::sub_dir("tun") else { return };
        if was || dir.join("pid").exists() {
            let _ = fs::write(dir.join("stop"), b"");
            for _ in 0..20 {
                std::thread::sleep(Duration::from_millis(200));
                if !dir.join("stop").exists() {
                    break;
                }
            }
            let _ = fs::remove_file(dir.join("pid"));
            if was {
                log::info!(target: "freeport", "[*] tun: stopped");
            }
        }
    }

    pub fn is_running(&self) -> bool {
        *self.running.lock()
    }
}

/// Freeport's own programs: their traffic must never loop back into the interface.
fn own_paths() -> Vec<String> {
    let mut paths = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        // The whole bundle on macOS, the install folder on Windows.
        let base = exe.ancestors().find(|p| p.extension().is_some_and(|e| e == "app")).map(Path::to_path_buf);
        if let Some(dir) = base.or_else(|| exe.parent().map(Path::to_path_buf)) {
            paths.push(dir.to_string_lossy().into_owned());
        }
    }
    if let Ok(data) = settings::data_dir() {
        paths.push(data.to_string_lossy().into_owned());
    }
    paths
}

fn install(dir: &Path) -> Result<PathBuf> {
    let path = dir.join(format!("sing-box{EXT}"));
    let current = fs::metadata(&path).map(|m| m.len() as usize == SINGBOX.len()).unwrap_or(false);
    if !current {
        let staging = path.with_extension("new");
        fs::write(&staging, SINGBOX).context("could not write the TUN helper")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&staging, fs::Permissions::from_mode(0o755))?;
        }
        let _ = fs::remove_file(&path);
        fs::rename(&staging, &path).context("could not install the TUN helper")?;
    }
    Ok(path)
}

#[cfg(unix)]
fn alive(pid: u32) -> bool {
    // A root process answers a signal 0 from us with EPERM: it exists.
    let Ok(pid) = i32::try_from(pid) else { return false };
    // SAFETY: signal 0 only checks that the process exists.
    let result = unsafe { libc::kill(pid, 0) };
    result == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(windows)]
fn alive(pid: u32) -> bool {
    use std::os::windows::process::CommandExt;
    std::process::Command::new("tasklist")
        .args(["/FI", &format!("PID eq {pid}"), "/NH"])
        .creation_flags(0x0800_0000)
        .output()
        .map(|out| String::from_utf8_lossy(&out.stdout).contains(&pid.to_string()))
        .unwrap_or(false)
}

fn quote_sh(path: &Path) -> String {
    format!("'{}'", path.to_string_lossy().replace('\'', "'\\''"))
}

#[cfg(target_os = "macos")]
fn elevate(dir: &Path, binary: &Path, config: &Path, stop: &Path, pid: &Path) -> Result<()> {
    let watchdog = dir.join("watchdog.sh");
    fs::write(
        &watchdog,
        "#!/bin/sh\n\
         \"$1\" run -c \"$2\" >\"$5\" 2>&1 &\n\
         P=$!\n\
         echo $P >\"$4\"\n\
         while kill -0 \"$6\" 2>/dev/null && [ ! -f \"$3\" ] && kill -0 $P 2>/dev/null; do sleep 1; done\n\
         kill $P 2>/dev/null; sleep 1; kill -9 $P 2>/dev/null\n\
         rm -f \"$3\"\n",
    )?;
    let log = dir.join("singbox.log");
    let command = format!(
        "/bin/sh {} {} {} {} {} {} {} >/dev/null 2>&1 &",
        quote_sh(&watchdog),
        quote_sh(binary),
        quote_sh(config),
        quote_sh(stop),
        quote_sh(pid),
        quote_sh(&log),
        std::process::id()
    );
    let script = format!(
        "do shell script \"{}\" with prompt \"Freeport needs your password to turn on TUN mode.\" with administrator privileges",
        command.replace('\\', "\\\\").replace('"', "\\\"")
    );
    let out = std::process::Command::new("osascript").args(["-e", &script]).output().context("could not ask for administrator rights")?;
    if !out.status.success() {
        bail!("administrator rights were not given");
    }
    Ok(())
}

#[cfg(windows)]
fn elevate(dir: &Path, binary: &Path, config: &Path, stop: &Path, pid: &Path) -> Result<()> {
    use std::os::windows::process::CommandExt;
    let watchdog = dir.join("watchdog.ps1");
    fs::write(
        &watchdog,
        "param($sb, $cfg, $stop, $pidfile, $log, $app)\r\n\
         $p = Start-Process -FilePath $sb -ArgumentList @('run','-c',('\"' + $cfg + '\"')) -WindowStyle Hidden -PassThru -RedirectStandardError $log\r\n\
         Set-Content -Path $pidfile -Value $p.Id\r\n\
         while ((Get-Process -Id $app -ErrorAction SilentlyContinue) -and -not (Test-Path $stop) -and -not $p.HasExited) { Start-Sleep -Seconds 1 }\r\n\
         Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue\r\n\
         Remove-Item -Path $stop -ErrorAction SilentlyContinue\r\n",
    )?;
    let arg = |p: &Path| format!("'\"{}\"'", p.to_string_lossy().replace('\'', "''"));
    let inner = format!(
        "Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File',{},{},{},{},{},{},'{}'",
        arg(&watchdog),
        arg(binary),
        arg(config),
        arg(stop),
        arg(pid),
        arg(&dir.join("singbox.log")),
        std::process::id()
    );
    let out = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &inner])
        .creation_flags(0x0800_0000)
        .output()
        .context("could not ask for administrator rights")?;
    if !out.status.success() {
        bail!("administrator rights were not given");
    }
    Ok(())
}

#[cfg(not(any(target_os = "macos", windows)))]
fn elevate(_: &Path, _: &Path, _: &Path, _: &Path, _: &Path) -> Result<()> {
    bail!("TUN mode is not supported here")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn everything_goes_to_the_proxy_but_freeport_itself() {
        let value = config(1819, &Scope::Everything, &["/Applications/Freeport.app".into()]);
        assert_eq!(value["route"]["final"], "proxy");
        assert_eq!(value["outbounds"][0]["server_port"], 1819);
        let own = &value["route"]["rules"][2]["process_path_regex"][0];
        assert!(own.as_str().unwrap().ends_with("Freeport\\.app"), "{own}");
    }

    #[test]
    fn per_app_sends_only_chosen_apps_and_their_dns() {
        let value = config(1819, &Scope::Apps(vec!["/Applications/Telegram.app".into()]), &[]);
        assert_eq!(value["route"]["final"], "direct");
        assert_eq!(value["dns"]["final"], "local");
        let rules = value["route"]["rules"].as_array().unwrap();
        assert_eq!(rules.last().unwrap()["outbound"], "proxy");
        assert_eq!(value["dns"]["rules"][0]["server"], "remote");
    }

    #[test]
    fn paths_become_anchored_literal_patterns() {
        let pattern = path_pattern("/Applications/A (1).app/");
        assert!(pattern.ends_with("/Applications/A \\(1\\)\\.app"));
        assert!(pattern.contains('^'));
    }

    /// sing-box itself accepts both shapes of the configuration.
    #[test]
    fn sing_box_accepts_the_configuration() {
        if !is_available() {
            return;
        }
        let dir = std::env::temp_dir().join(format!("freeport-tun-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let binary = install(&dir).unwrap();
        for scope in [Scope::Everything, Scope::Apps(vec!["/Applications/Safari.app".into()])] {
            let path = dir.join("config.json");
            fs::write(&path, serde_json::to_vec(&config(1819, &scope, &["/Applications/Freeport.app".into()])).unwrap()).unwrap();
            let out = std::process::Command::new(&binary).args(["check", "-c"]).arg(&path).output().unwrap();
            assert!(out.status.success(), "{scope:?}: {}", String::from_utf8_lossy(&out.stderr));
        }
        let _ = fs::remove_dir_all(&dir);
    }
}
