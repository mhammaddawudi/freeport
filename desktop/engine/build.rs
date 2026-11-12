//! Picks the helper programs built for the target and hands their paths to
//! the engine, which embeds them: Psiphon's console client (required), and
//! Arti with Snowflake for Tor (optional; without them Tor is reported as
//! missing from the build). CI builds them into assets/; locally,
//! FREEPORT_PSIPHON, FREEPORT_ARTI and FREEPORT_SNOWFLAKE may point anywhere.

use std::path::PathBuf;

fn main() {
    let os = std::env::var("CARGO_CFG_TARGET_OS").unwrap();
    let arch = match std::env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() {
        "aarch64" => "arm64",
        "x86_64" => "amd64",
        other => panic!("no helper builds for {other}"),
    };
    let (goos, ext) = match os.as_str() {
        "windows" => ("windows", ".exe"),
        "macos" => ("darwin", ""),
        other => (other, ""),
    };
    let assets = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("assets");
    let find = |var: &str, stem: &str| {
        println!("cargo:rerun-if-env-changed={var}");
        let path = std::env::var(var).map(PathBuf::from).unwrap_or_else(|_| assets.join(format!("{stem}-{goos}-{arch}{ext}")));
        println!("cargo:rerun-if-changed={}", path.display());
        path
    };

    let psiphon = find("FREEPORT_PSIPHON", "psiphon");
    if !psiphon.exists() {
        panic!("{} is missing: build it with scripts/build-psiphon.sh, or set FREEPORT_PSIPHON", psiphon.display());
    }
    println!("cargo:rustc-env=FREEPORT_PSIPHON={}", psiphon.display());

    // Tor is optional: an empty stand-in keeps the build going without it.
    let empty = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("absent");
    std::fs::write(&empty, b"").unwrap();
    for (var, stem) in [("FREEPORT_ARTI", "arti"), ("FREEPORT_SNOWFLAKE", "snowflake"), ("FREEPORT_SINGBOX", "singbox")] {
        let path = find(var, stem);
        let chosen = if path.exists() { path } else { empty.clone() };
        println!("cargo:rustc-env={var}={}", chosen.display());
    }
}
