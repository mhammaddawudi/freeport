//! Per-app proxy: opens chosen apps pointed at Freeport, so only they use
//! the tunnel. Each app gets the proxy in its environment and, since
//! Chromium and Electron apps ignore that, as `--proxy-server` too.

use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{bail, Context, Result};
use serde::Serialize;

#[derive(Serialize, Clone, PartialEq, Eq, Debug)]
pub struct App {
    pub name: String,
    pub path: String,
}

/// The apps installed for this user, by name.
pub fn installed() -> Vec<App> {
    let mut apps: Vec<App> = roots().iter().flat_map(|root| scan(root, 0)).collect();
    apps.sort_by_key(|app| app.name.to_lowercase());
    apps.dedup_by(|a, b| a.name.eq_ignore_ascii_case(&b.name));
    apps
}

/// The arguments that point an app at Freeport.
pub fn proxy_args(socks: u16) -> Vec<String> {
    vec![format!("--proxy-server=socks5://127.0.0.1:{socks}"), "--proxy-bypass-list=<-loopback>".into()]
}

/// The environment that points an app at Freeport.
pub fn proxy_env(socks: u16, http: u16) -> Vec<(String, String)> {
    let http_url = format!("http://127.0.0.1:{http}");
    let socks_url = format!("socks5h://127.0.0.1:{socks}");
    [("HTTP_PROXY", &http_url), ("HTTPS_PROXY", &http_url), ("ALL_PROXY", &socks_url)]
        .into_iter()
        .flat_map(|(key, value)| [(key.to_string(), value.clone()), (key.to_lowercase(), value.clone())])
        .chain([("NO_PROXY".to_string(), "localhost,127.0.0.1".to_string())])
        .collect()
}

/// Opens `path` through Freeport. A known app only: anything else is refused.
pub fn open(path: &str, socks: u16, http: u16) -> Result<()> {
    if !installed().iter().any(|app| app.path == path) {
        bail!("{path} is not an installed app");
    }
    launch(Path::new(path), socks, http)
}

#[cfg(target_os = "macos")]
fn roots() -> Vec<PathBuf> {
    let mut roots = vec![PathBuf::from("/Applications")];
    if let Some(home) = std::env::var_os("HOME") {
        roots.push(PathBuf::from(home).join("Applications"));
    }
    roots
}

#[cfg(target_os = "macos")]
fn scan(dir: &Path, depth: u8) -> Vec<App> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    entries
        .flatten()
        .flat_map(|entry| {
            let path = entry.path();
            if path.extension().is_some_and(|ext| ext == "app") {
                vec![App { name: stem(&path), path: path.to_string_lossy().into() }]
            } else if depth < 1 && path.is_dir() {
                scan(&path, depth + 1)
            } else {
                Vec::new()
            }
        })
        .collect()
}

#[cfg(target_os = "macos")]
fn launch(app: &Path, socks: u16, http: u16) -> Result<()> {
    // A second instance, so an app already open without the proxy gets one with it.
    let mut command = Command::new("open");
    command.arg("-n").arg("-a").arg(app);
    for (key, value) in proxy_env(socks, http) {
        command.arg("--env").arg(format!("{key}={value}"));
    }
    command.arg("--args").args(proxy_args(socks));
    let status = command.status().context("could not run open")?;
    if !status.success() {
        bail!("{} did not open", stem(app));
    }
    Ok(())
}

#[cfg(windows)]
fn roots() -> Vec<PathBuf> {
    ["ProgramData", "APPDATA"]
        .iter()
        .filter_map(std::env::var_os)
        .map(|base| PathBuf::from(base).join(r"Microsoft\Windows\Start Menu\Programs"))
        .collect()
}

#[cfg(windows)]
fn scan(dir: &Path, depth: u8) -> Vec<App> {
    let Ok(entries) = std::fs::read_dir(dir) else { return Vec::new() };
    entries
        .flatten()
        .flat_map(|entry| {
            let path = entry.path();
            let name = stem(&path);
            let skip = ["uninstall", "readme", "help", "website"].iter().any(|word| name.to_lowercase().contains(word));
            if path.extension().is_some_and(|ext| ext.eq_ignore_ascii_case("lnk")) && !skip {
                vec![App { name, path: path.to_string_lossy().into() }]
            } else if depth < 2 && path.is_dir() {
                scan(&path, depth + 1)
            } else {
                Vec::new()
            }
        })
        .collect()
}

#[cfg(windows)]
fn launch(shortcut: &Path, socks: u16, http: u16) -> Result<()> {
    use std::os::windows::process::CommandExt;
    // The shortcut's own target and arguments, so the proxy flag can be added.
    let script = format!(
        "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('{}'); $s.TargetPath; $s.Arguments; $s.WorkingDirectory",
        shortcut.to_string_lossy().replace('\'', "''")
    );
    let out = Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .creation_flags(0x0800_0000)
        .output()
        .context("could not read the shortcut")?;
    let text = String::from_utf8_lossy(&out.stdout).to_string();
    let mut lines = text.lines().map(str::trim);
    let target = lines.next().unwrap_or("").to_string();
    let args = lines.next().unwrap_or("").to_string();
    let dir = lines.next().unwrap_or("").to_string();
    if target.is_empty() || !target.to_lowercase().ends_with(".exe") {
        bail!("{} cannot be opened through Freeport", stem(shortcut));
    }
    let mut command = Command::new(&target);
    command.envs(proxy_env(socks, http)).args(proxy_args(socks));
    if !args.is_empty() {
        command.raw_arg(&args);
    }
    if !dir.is_empty() {
        command.current_dir(dir);
    }
    command.spawn().with_context(|| format!("{} did not open", stem(shortcut)))?;
    Ok(())
}

#[cfg(not(any(target_os = "macos", windows)))]
fn roots() -> Vec<PathBuf> {
    Vec::new()
}

#[cfg(not(any(target_os = "macos", windows)))]
fn scan(_dir: &Path, _depth: u8) -> Vec<App> {
    Vec::new()
}

#[cfg(not(any(target_os = "macos", windows)))]
fn launch(_app: &Path, _socks: u16, _http: u16) -> Result<()> {
    bail!("per-app proxy is not supported here")
}

fn stem(path: &Path) -> String {
    path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn apps_get_both_proxy_forms() {
        let env = proxy_env(1819, 1820);
        assert!(env.contains(&("HTTPS_PROXY".into(), "http://127.0.0.1:1820".into())));
        assert!(env.contains(&("all_proxy".into(), "socks5h://127.0.0.1:1819".into())));
        assert_eq!(proxy_args(1819)[0], "--proxy-server=socks5://127.0.0.1:1819");
    }

    #[test]
    fn unknown_paths_are_refused() {
        assert!(open("/bin/sh", 1819, 1820).is_err());
    }
}
