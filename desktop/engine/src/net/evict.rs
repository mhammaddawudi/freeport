//! Freeing a local port another program holds, usually another VPN client
//! left listening on the ports Freeport uses.

use std::process::Command;
use std::time::Duration;

use super::ports;

/// A program listening on one of our ports.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Holder {
    pub pid: u32,
    pub name: String,
}

/// Stops whatever listens on `port`, then waits for the port to come free.
/// Returns the programs it stopped; never touches this process or the system.
pub async fn evict(port: u16) -> Vec<Holder> {
    let own = std::process::id();
    let holders: Vec<Holder> = holders(port).into_iter().filter(|h| h.pid != own && !is_system(h.pid)).collect();
    for holder in &holders {
        log::warn!(target: "freeport", "[*] port {port} is held by {} (pid {}); stopping it", holder.name, holder.pid);
        stop(holder.pid, false);
    }
    if !holders.is_empty() && !ports::await_released(port, Duration::from_secs(3)).await {
        holders.iter().for_each(|holder| stop(holder.pid, true));
        ports::await_released(port, Duration::from_secs(3)).await;
    }
    holders
}

fn is_system(pid: u32) -> bool {
    // 0 and 1 are the kernel and init/launchd; 4 is the Windows System process.
    pid <= 1 || (cfg!(windows) && pid == 4)
}

/// The listeners on `port`.
pub fn holders(port: u16) -> Vec<Holder> {
    let mut pids = listening_pids(port);
    pids.sort_unstable();
    pids.dedup();
    pids.into_iter().map(|pid| Holder { pid, name: name_of(pid) }).collect()
}

#[cfg(unix)]
fn listening_pids(port: u16) -> Vec<u32> {
    let Ok(out) = Command::new("lsof").args(["-nP", "-t", &format!("-iTCP:{port}"), "-sTCP:LISTEN"]).output() else {
        return Vec::new();
    };
    parse_pids(&String::from_utf8_lossy(&out.stdout))
}

#[cfg(unix)]
fn parse_pids(text: &str) -> Vec<u32> {
    text.lines().filter_map(|line| line.trim().parse().ok()).collect()
}

#[cfg(windows)]
fn listening_pids(port: u16) -> Vec<u32> {
    let Ok(out) = hidden(Command::new("netstat").args(["-ano", "-p", "TCP"])).output() else {
        return Vec::new();
    };
    parse_netstat(&String::from_utf8_lossy(&out.stdout), port)
}

/// Picks the owners of LISTENING rows on `port` out of `netstat -ano`.
#[cfg_attr(not(windows), allow(dead_code))]
fn parse_netstat(text: &str, port: u16) -> Vec<u32> {
    let suffix = format!(":{port}");
    text.lines()
        .filter_map(|line| {
            let cols: Vec<&str> = line.split_whitespace().collect();
            match cols.as_slice() {
                [_, local, _, state, pid] if local.ends_with(&suffix) && state.eq_ignore_ascii_case("LISTENING") => pid.parse().ok(),
                _ => None,
            }
        })
        .collect()
}

#[cfg(unix)]
fn name_of(pid: u32) -> String {
    Command::new("ps")
        .args(["-p", &pid.to_string(), "-o", "comm="])
        .output()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().rsplit('/').next().unwrap_or("").to_string())
        .ok()
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "another program".into())
}

#[cfg(windows)]
fn name_of(pid: u32) -> String {
    let filter = format!("PID eq {pid}");
    hidden(Command::new("tasklist").args(["/FI", &filter, "/NH", "/FO", "CSV"]))
        .output()
        .ok()
        .and_then(|out| {
            let text = String::from_utf8_lossy(&out.stdout).to_string();
            text.split(',').next().map(|first| first.trim().trim_matches('"').to_string())
        })
        .filter(|name| !name.is_empty() && !name.starts_with("INFO"))
        .unwrap_or_else(|| "another program".into())
}

#[cfg(unix)]
fn stop(pid: u32, force: bool) {
    let Ok(pid) = i32::try_from(pid) else { return };
    // SAFETY: a plain signal to a process found listening on our port.
    unsafe { libc::kill(pid, if force { libc::SIGKILL } else { libc::SIGTERM }) };
}

#[cfg(windows)]
fn stop(pid: u32, _force: bool) {
    let _ = hidden(Command::new("taskkill").args(["/PID", &pid.to_string(), "/T", "/F"])).output();
}

#[cfg(windows)]
fn hidden(command: &mut Command) -> &mut Command {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn netstat_rows_are_read_for_the_port_only() {
        let text = "\
  Proto  Local Address          Foreign Address        State           PID
  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1012
  TCP    127.0.0.1:1820         0.0.0.0:0              LISTENING       5512
  TCP    127.0.0.1:18200        0.0.0.0:0              LISTENING       77
  TCP    127.0.0.1:1820         127.0.0.1:50000        ESTABLISHED     5512
  TCP    [::]:1820              [::]:0                 LISTENING       6100
";
        assert_eq!(parse_netstat(text, 1820), vec![5512, 6100]);
    }

    #[test]
    fn system_processes_are_never_stopped() {
        assert!(is_system(0));
        assert!(is_system(1));
        assert!(!is_system(std::process::id().max(2)));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn a_listener_is_found_by_its_port() {
        let _serial = crate::net::serial();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let found = holders(port);
        assert!(found.iter().any(|h| h.pid == std::process::id()), "{found:?}");
        // Our own process is never evicted.
        assert!(evict(port).await.is_empty());
    }
}
