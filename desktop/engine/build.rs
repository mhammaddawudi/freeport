//! Picks the Psiphon console client built for the target and hands its path
//! to the engine, which embeds it. CI builds them into assets/; locally,
//! FREEPORT_PSIPHON may point anywhere.

use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=FREEPORT_PSIPHON");
    let os = std::env::var("CARGO_CFG_TARGET_OS").unwrap();
    let arch = match std::env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() {
        "aarch64" => "arm64",
        "x86_64" => "amd64",
        other => panic!("no psiphon build for {other}"),
    };
    let name = match os.as_str() {
        "windows" => format!("psiphon-windows-{arch}.exe"),
        "macos" => format!("psiphon-darwin-{arch}"),
        other => format!("psiphon-{other}-{arch}"),
    };
    let path = match std::env::var("FREEPORT_PSIPHON") {
        Ok(path) => PathBuf::from(path),
        Err(_) => PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("assets").join(&name),
    };
    if !path.exists() {
        panic!("{} is missing: build it with scripts/build-psiphon.sh, or set FREEPORT_PSIPHON", path.display());
    }
    println!("cargo:rerun-if-changed={}", path.display());
    println!("cargo:rustc-env=FREEPORT_PSIPHON={}", path.display());
}
