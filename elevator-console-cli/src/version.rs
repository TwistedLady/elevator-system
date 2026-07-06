//! `version` subcommand: show the console's own version and check it matches the backend API.
//! The console is the frontend here — it displays both versions and fails on a mismatch, so an
//! operator can't unknowingly drive a backend built from a different release.
use crate::{api, BoxErr};

/// Baked in at compile time from the repo-root VERSION file (see build.rs).
pub const CONSOLE_VERSION: &str = env!("APP_VERSION");

pub fn run(api_base: &str) -> Result<(), BoxErr> {
    println!("console version: {CONSOLE_VERSION}");
    let backend = api::get_version(&api::agent(), api_base)
        .map_err(|e| format!("could not read backend version from {api_base}: {e}"))?;
    println!("backend version: {backend}");
    if backend == CONSOLE_VERSION {
        println!("✓ versions match");
        Ok(())
    } else {
        Err(format!("✗ version mismatch: console {CONSOLE_VERSION} != backend {backend}").into())
    }
}
