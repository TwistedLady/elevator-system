//! `version` subcommand: show the console version and fail on a mismatch with the backend,
//! so an operator can't unknowingly drive a backend from a different release.
//! CONSOLE_VERSION is baked in at build time from the repo-root VERSION file (build.rs).
use crate::{api, BoxErr};

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
