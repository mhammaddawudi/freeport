//! What the window is sent: the engine's status and log lines, flattened
//! into plain JSON the page can read without knowing the engine's types.

use std::time::UNIX_EPOCH;

use freeport_engine::engine::{Phase, Status};
use freeport_engine::logbus::Line;
use freeport_engine::model::{self, Protocol};
use freeport_engine::net::exit::ClaudeReach;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct StatusView {
    /// idle, preparing, scanning, connecting, unlocking, connected, reconnecting, disconnecting
    pub phase: &'static str,
    pub reason: Option<String>,
    pub attempt: Option<u32>,
    pub protocol: Option<Protocol>,
    pub protocol_name: Option<&'static str>,
    pub via_auto: bool,
    pub endpoint: Option<String>,
    pub rtt_ms: Option<u64>,
    pub connected_secs: Option<u64>,
    pub relayed: bool,
    pub exit_country: Option<String>,
    pub exit_flag: Option<String>,
    pub exit_ip: Option<String>,
    /// Whether Claude's API answers through this exit; null until checked.
    pub claude_open: Option<bool>,
    pub latency_ms: Option<u64>,
}

impl StatusView {
    pub fn of(status: &Status) -> StatusView {
        let (phase, reason, attempt) = match &status.phase {
            Phase::Idle => ("idle", None, None),
            Phase::Preparing => ("preparing", None, None),
            Phase::Scanning => ("scanning", None, None),
            Phase::Connecting => ("connecting", None, None),
            Phase::Unlocking => ("unlocking", None, None),
            Phase::Connected => ("connected", None, None),
            Phase::Reconnecting { attempt, reason } => ("reconnecting", Some(reason.clone()), Some(*attempt)),
            Phase::Disconnecting => ("disconnecting", None, None),
        };
        StatusView {
            phase,
            reason,
            attempt,
            protocol: status.protocol,
            protocol_name: status.protocol.map(Protocol::name),
            via_auto: status.via_auto,
            endpoint: status.endpoint.as_ref().map(|edge| edge.socket().to_string()),
            rtt_ms: status.endpoint.as_ref().map(|edge| edge.rtt_ms),
            connected_secs: status.since.map(|since| since.elapsed().as_secs()),
            relayed: status.relayed,
            exit_country: status.exit.as_ref().map(|trace| trace.country.clone()),
            exit_flag: status.exit.as_ref().map(|trace| model::flag(&trace.country)),
            exit_ip: status.exit.as_ref().map(|trace| trace.ip.clone()),
            claude_open: status.claude.map(|reach| reach == ClaudeReach::Open),
            latency_ms: status.latency.last().copied(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct LogView {
    pub index: usize,
    /// Milliseconds since the epoch.
    pub at: u64,
    pub level: &'static str,
    pub text: String,
}

impl LogView {
    pub fn of(index: usize, line: &Line) -> LogView {
        LogView {
            index,
            at: line.at.duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0),
            level: match line.level {
                log::Level::Error => "error",
                log::Level::Warn => "warn",
                log::Level::Info => "info",
                _ => "debug",
            },
            text: line.text.clone(),
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize)]
pub struct Traffic {
    pub up: u64,
    pub down: u64,
}
