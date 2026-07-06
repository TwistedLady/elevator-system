//! Bakes the repo-root VERSION file into the binary at compile time as APP_VERSION, so the console
//! reports the same single source of truth as the backend and the web console. Rebuilds whenever
//! VERSION changes.
use std::path::Path;

fn main() {
    let version_path = Path::new("../VERSION");
    let version = std::fs::read_to_string(version_path)
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|_| "unknown".to_string());
    println!("cargo:rustc-env=APP_VERSION={version}");
    println!("cargo:rerun-if-changed=../VERSION");
}
