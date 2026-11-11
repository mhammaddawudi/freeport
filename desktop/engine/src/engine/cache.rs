//! The last edge that worked for each protocol, tried before any scan.

use std::collections::HashMap;
use std::fs;
use std::net::IpAddr;
use std::path::PathBuf;

use warp::api::Endpoint;
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};

use crate::model::{IpVersion, Protocol};
use crate::settings;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
struct Remembered {
    ip: IpAddr,
    port: u16,
    rtt_ms: u64,
}

#[derive(Default)]
pub struct EndpointCache {
    entries: Mutex<HashMap<String, Remembered>>,
}

fn key(protocol: Protocol, ip: IpVersion) -> String {
    format!("{}/{}", serde_json::to_string(&protocol).unwrap_or_default().trim_matches('"'), ip.wire())
}

fn file() -> Option<PathBuf> {
    settings::data_dir().ok().map(|dir| dir.join("endpoints.json"))
}

impl EndpointCache {
    pub fn load() -> EndpointCache {
        let entries = file()
            .and_then(|path| fs::read_to_string(path).ok())
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default();
        EndpointCache { entries: Mutex::new(entries) }
    }

    pub fn recall(&self, protocol: Protocol, ip: IpVersion) -> Option<Endpoint> {
        self.entries
            .lock()
            .get(&key(protocol, ip))
            .map(|r| Endpoint { ip: r.ip, port: r.port, rtt_ms: r.rtt_ms })
    }

    pub fn remember(&self, protocol: Protocol, ip: IpVersion, endpoint: Endpoint) {
        let entry = Remembered { ip: endpoint.ip, port: endpoint.port, rtt_ms: endpoint.rtt_ms };
        let snapshot = {
            let mut entries = self.entries.lock();
            entries.insert(key(protocol, ip), entry);
            entries.clone()
        };
        self.persist(&snapshot);
    }

    pub fn forget(&self, protocol: Protocol, ip: IpVersion) {
        let snapshot = {
            let mut entries = self.entries.lock();
            entries.remove(&key(protocol, ip));
            entries.clone()
        };
        self.persist(&snapshot);
    }

    fn persist(&self, entries: &HashMap<String, Remembered>) {
        let (Some(path), Ok(text)) = (file(), serde_json::to_string_pretty(entries)) else { return };
        if let Err(e) = fs::write(&path, text) {
            log::debug!(target: "freeport", "[-] could not save the endpoint cache: {e}");
        }
    }
}
