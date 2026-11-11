//! Keeps macOS from napping the app.
//!
//! A window hidden in the tray is an App Nap candidate: macOS then coalesces
//! timers and lowers the priority of the app and of Psiphon, its child, which
//! slows the scan, the tunnel and every page load. A terminal running the CLI
//! is never napped, which is why the same engine ran faster there.

/// Holds the activity for the life of the process.
#[cfg(target_os = "macos")]
pub fn hold() {
    use objc2_foundation::{NSActivityOptions, NSProcessInfo, NSString};
    let reason = NSString::from_str("Freeport carries network traffic");
    let options = NSActivityOptions::UserInitiatedAllowingIdleSystemSleep | NSActivityOptions::LatencyCritical;
    let token = NSProcessInfo::processInfo().beginActivityWithOptions_reason(options, &reason);
    // Never ended: the activity lasts as long as the app does.
    std::mem::forget(token);
}

#[cfg(not(target_os = "macos"))]
pub fn hold() {}
