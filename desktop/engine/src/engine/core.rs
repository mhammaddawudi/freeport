//! The Rust core, called in process: identities, scans and tunnels.

use std::net::SocketAddr;
use std::sync::Arc;

use warp::account::Identity;
use warp::api::{self, Cancel, Endpoint, ScanRequest, Transport, TunnelSpec};
use anyhow::{anyhow, Result};
use parking_lot::Mutex;
use tokio::task::JoinHandle;

use crate::model::Protocol;
use crate::settings::{self, Settings};

/// A tunnel the core is running. Dropping it stops the tunnel, which is what
/// makes aborting a session safe at any await point.
pub struct CoreJob {
    cancel: Cancel,
    handle: JoinHandle<Result<()>>,
}

impl CoreJob {
    pub fn is_finished(&self) -> bool {
        self.handle.is_finished()
    }

    /// Why the job ended, once it has.
    pub async fn outcome(&mut self) -> String {
        match (&mut self.handle).await {
            Ok(Ok(())) => "the core closed the tunnel".to_string(),
            Ok(Err(e)) => format!("{e:#}"),
            Err(_) => "the tunnel task stopped".to_string(),
        }
    }
}

impl Drop for CoreJob {
    fn drop(&mut self) {
        self.cancel.cancel();
        self.handle.abort();
    }
}

#[derive(Default)]
pub struct Core {
    identities: Mutex<std::collections::HashMap<&'static str, Arc<Identity>>>,
    probes: Mutex<std::collections::HashMap<&'static str, Arc<Identity>>>,
    ech: tokio::sync::Mutex<Option<Option<Vec<u8>>>>,
}

impl Core {
    /// Sets the switches the core reads from the environment rather than
    /// from a request. Each is written both ways every time: a variable left
    /// "on" by an H2 session would otherwise turn the next MASQUE one into H2.
    pub fn apply_environment(&self, settings: &Settings) {
        let protocol = settings.protocol;
        let fragment = protocol.is_tcp() && settings.fragment;
        set("WARP_MASQUE_HTTP2", if protocol.is_tcp() { "1" } else { "0" });
        set("WARP_MASQUE_H2_FRAGMENT", if fragment { "1" } else { "0" });
        set("WARP_QUICK_RECONNECT", "0");
        // Iranian lines regularly see 2-5s round trips to the edges; the
        // core's own 30s/10s deadlines give up on tunnels that would come up.
        set("WARP_MASQUE_STARTUP_SECS", "60");
        set("WARP_MASQUE_VALIDATE_SECS", "20");
        set("WARP_WG_VALIDATE_SECS", "15");
        set("WARP_WG_KEEPALIVE", &settings.keepalive_secs.to_string());
        // Always set: the variable outlives the session, and an empty one keeps the core's default.
        set("WARP_MASQUE_MTU", &if settings.masque_mtu > 0 { settings.masque_mtu.to_string() } else { String::new() });
    }

    pub async fn identity(&self, protocol: Protocol) -> Result<Arc<Identity>> {
        self.open(protocol, "identity", &self.identities).await
    }

    /// A second identity for background trials, so they never share an
    /// address with the person's own tunnel.
    pub async fn probe_identity(&self, protocol: Protocol) -> Result<Arc<Identity>> {
        self.open(protocol, "identity/probe", &self.probes).await
    }

    async fn open(
        &self,
        protocol: Protocol,
        dir: &str,
        slots: &Mutex<std::collections::HashMap<&'static str, Arc<Identity>>>,
    ) -> Result<Arc<Identity>> {
        let key = protocol.transport();
        if let Some(identity) = slots.lock().get(key).cloned() {
            return Ok(identity);
        }
        let transport = Transport::parse(key);
        let folder = settings::sub_dir(dir)?;
        migrate_legacy(&folder);
        let base = folder.join("warp.toml");
        let path = api::identity_path(&base.to_string_lossy(), transport, None);
        log::info!(target: "freeport", "[*] opening the {} identity", transport.label());
        let request = api::ProvisionRequest::for_transport(transport);
        let identity = Arc::new(api::open_identity(&path, &request).await.map_err(|e| anyhow!("{e}"))?);
        log::info!(target: "freeport", "[+] identity ready: device={} ipv4={}", identity.device_id, identity.ipv4);
        slots.lock().insert(key, identity.clone());
        Ok(identity)
    }

    /// The ECH config, looked up once per run: it is the same for every edge.
    async fn ech(&self, settings: &Settings) -> Option<Vec<u8>> {
        if !settings.fetch_ech || settings.protocol.transport() != "masque" || settings.protocol.is_tcp() {
            return None;
        }
        let mut cached = self.ech.lock().await;
        if cached.is_none() {
            let fetched = api::fetch_ech_config().await;
            if let Err(e) = &fetched {
                log::warn!(target: "freeport", "[-] no ECH key, handshakes go without it: {e}");
            }
            *cached = Some(fetched.ok());
        }
        cached.clone().flatten()
    }

    pub async fn scan(
        &self,
        identity: &Identity,
        settings: &Settings,
        excluded: &std::collections::HashSet<SocketAddr>,
        cancel: &Cancel,
    ) -> Result<Endpoint> {
        if let Some(peer) = pinned(settings) {
            return Ok(Endpoint { ip: peer.ip(), port: peer.port(), rtt_ms: 0 });
        }
        let protocol = settings.protocol;
        let mut request = ScanRequest::for_transport(Transport::parse(protocol.transport()))
            .with_profile(settings.effective_noize().wire())
            .with_mode(settings.scan_mode.wire_for(protocol))
            .with_ip(warp::prober::IpScan::parse(settings.ip_version.wire()));
        if protocol.is_tcp() {
            // HTTP/2 rides TCP 443; the other MASQUE ports are QUIC-only and
            // would spend most of the scan budget on probes that cannot answer.
            request.ports = vec![443];
        }
        request.ech_config_list = self.ech(settings).await;
        request.excluded = excluded.clone();
        api::scan(identity, &request, cancel).await.map_err(|e| anyhow!("{e}"))
    }

    pub async fn start_tunnel(
        &self,
        identity: Arc<Identity>,
        endpoint: Endpoint,
        settings: &Settings,
        socks_port: u16,
    ) -> CoreJob {
        let transport = Transport::parse(settings.protocol.transport());
        let mut spec = TunnelSpec::for_transport(transport)
            .with_socks(SocketAddr::from(([127, 0, 0, 1], socks_port)))
            .with_profile(settings.effective_noize().wire());
        spec.keepalive = settings.keepalive_secs;
        spec.ech = self.ech(settings).await;

        let cancel = Cancel::new();
        let token = cancel.clone();
        let handle = tokio::spawn(async move {
            api::connect(&identity, endpoint.socket(), &spec, &token).await.map_err(|e| anyhow!("{e}"))
        });
        CoreJob { cancel, handle }
    }

    /// Warp in warp, run by the core's own CLI path end to end.
    pub fn start_managed(&self, settings: &Settings, socks_port: u16) -> Result<CoreJob> {
        let config = settings::sub_dir("identity")?.join("warp.toml");
        let mut args: Vec<String> = vec![
            "--gool-classic".into(),
            "--scan".into(),
            settings.scan_mode.wire_for(Protocol::Gool).into(),
            "--noize".into(),
            settings.effective_noize().wire().into(),
            "--ip".into(),
            settings.ip_version.wire().into(),
            "--bind".into(),
            format!("127.0.0.1:{socks_port}"),
            "--keepalive".into(),
            settings.keepalive_secs.to_string(),
            "--config".into(),
            config.to_string_lossy().into_owned(),
            "--no-quick-reconnect".into(),
        ];
        if let Some(peer) = pinned(settings) {
            args.extend(["--wiw-outer".into(), peer.to_string()]);
        }
        let cancel = Cancel::new();
        let handle = tokio::spawn(async move { warp::run_with(args).await.map_err(|e| anyhow!("{e}")) });
        Ok(CoreJob { cancel, handle })
    }
}

fn pinned(settings: &Settings) -> Option<SocketAddr> {
    settings.pinned_peer.trim().parse().ok()
}

/// Identities saved by versions before the core was renamed keep working.
fn migrate_legacy(dir: &std::path::Path) {
    const OLD: &str = "aether";
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.contains(OLD) {
            let target = dir.join(name.replace(OLD, "warp"));
            if !target.exists() {
                let _ = std::fs::rename(entry.path(), target);
            }
        }
    }
}

fn set(key: &str, value: &str) {
    std::env::set_var(key, value);
}
