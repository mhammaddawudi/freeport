pub mod evict;
pub mod exit;
pub mod front;
pub mod http;
pub mod lan;
pub mod ports;
pub mod socks;
pub mod sysproxy;
pub mod tun;

/// Tests that bind ports and tests that spawn tools such as `lsof` run one at
/// a time: a child can inherit a listener for a moment and hold its port.
#[cfg(test)]
pub(crate) fn serial() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}
