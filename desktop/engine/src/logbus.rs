//! Collects the core's and Freeport's log lines for the dashboard.
//!
//! The core logs through the `log` crate and installs env_logger only when
//! nothing else has claimed the logger, so installing this first is enough
//! to bring every one of its lines here instead of onto the terminal.

use std::collections::VecDeque;
use std::io::Write;
use std::sync::OnceLock;
use std::time::SystemTime;

use log::{Level, LevelFilter, Log, Metadata, Record};
use parking_lot::Mutex;

const CAPACITY: usize = 2_000;

#[derive(Debug, Clone)]
pub struct Line {
    pub at: SystemTime,
    pub level: Level,
    pub text: String,
}

struct Bus {
    lines: Mutex<VecDeque<Line>>,
    echo: bool,
    verbose: bool,
}

static BUS: OnceLock<Bus> = OnceLock::new();

/// Installs the logger. `echo` also prints each line to stderr, for the
/// headless mode.
pub fn install(echo: bool, verbose: bool) {
    let bus = BUS.get_or_init(|| Bus { lines: Mutex::new(VecDeque::with_capacity(CAPACITY)), echo, verbose });
    if log::set_logger(&Logger).is_ok() {
        log::set_max_level(if bus.verbose { LevelFilter::Debug } else { LevelFilter::Info });
    }
}

/// Every line kept so far, oldest first.
pub fn snapshot() -> Vec<Line> {
    BUS.get().map(|bus| bus.lines.lock().iter().cloned().collect()).unwrap_or_default()
}

struct Logger;

impl Log for Logger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        let verbose = BUS.get().is_some_and(|bus| bus.verbose);
        let ours = metadata.target().starts_with("warp") || metadata.target().starts_with("freeport");
        match (ours, verbose) {
            (true, true) => metadata.level() <= Level::Debug,
            (true, false) => metadata.level() <= Level::Info,
            // Libraries only when something went wrong.
            (false, _) => metadata.level() <= Level::Warn,
        }
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let Some(bus) = BUS.get() else { return };
        let line = Line { at: SystemTime::now(), level: record.level(), text: record.args().to_string() };
        if bus.echo {
            let stamp = chrono_like(line.at);
            let _ = writeln!(std::io::stderr(), "{stamp} {:<5} {}", line.level, line.text);
        }
        let mut lines = bus.lines.lock();
        if lines.len() == CAPACITY {
            lines.pop_front();
        }
        lines.push_back(line);
    }

    fn flush(&self) {}
}

/// `HH:MM:SS` in local time, without pulling a date library in.
pub fn chrono_like(at: SystemTime) -> String {
    let secs = at.duration_since(SystemTime::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
    let local = secs + local_offset_secs();
    let day = local.rem_euclid(86_400);
    format!("{:02}:{:02}:{:02}", day / 3600, (day % 3600) / 60, day % 60)
}

fn local_offset_secs() -> i64 {
    static OFFSET: OnceLock<i64> = OnceLock::new();
    *OFFSET.get_or_init(|| {
        i64::from(chrono::Local::now().offset().local_minus_utc())
    })
}
