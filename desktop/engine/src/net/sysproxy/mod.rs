//! The system proxy: browsers and most desktop apps follow it, so pointing
//! it at Freeport carries them without any per-app setup. Whatever was set
//! before is saved and put back on disconnect, even after a crash.

#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "macos")]
pub use macos::{disable, enable, left_on};

#[cfg(windows)]
mod windows;
#[cfg(windows)]
pub use windows::{disable, enable, left_on};

#[cfg(not(any(target_os = "macos", windows)))]
mod unsupported {
    use anyhow::{bail, Result};

    pub fn enable(_socks_port: u16, _http_port: u16) -> Result<()> {
        bail!("this system has no system proxy Freeport can set")
    }

    pub fn disable() -> Result<()> {
        Ok(())
    }

    pub fn left_on() -> bool {
        false
    }
}
#[cfg(not(any(target_os = "macos", windows)))]
pub use unsupported::{disable, enable, left_on};
