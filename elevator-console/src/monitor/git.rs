//! Git status of the working tree the console runs in. Shown in the header. Uses the `git` CLI —
//! one of the console's allowed channels (api / kubectl / git), never Kafka.
use std::process::Command;

#[derive(Clone, Default)]
pub struct GitInfo {
    pub branch: String,
    pub sha: String,
    pub dirty: bool,
    pub available: bool,
}

impl GitInfo {
    /// Snapshot the current repo once (cheap; called at startup).
    pub fn snapshot() -> Self {
        match (
            run(&["rev-parse", "--abbrev-ref", "HEAD"]),
            run(&["rev-parse", "--short", "HEAD"]),
        ) {
            (Some(branch), Some(sha)) => GitInfo {
                branch,
                sha,
                dirty: run(&["status", "--porcelain"])
                    .map(|s| !s.trim().is_empty())
                    .unwrap_or(false),
                available: true,
            },
            _ => GitInfo::default(),
        }
    }

    /// Compact header label, e.g. `⎇ refactor-console-api@a2bfac9*`. Empty when not in a repo.
    pub fn label(&self) -> String {
        if !self.available {
            return String::new();
        }
        format!(
            "⎇ {}@{}{}",
            self.branch,
            self.sha,
            if self.dirty { "*" } else { "" }
        )
    }
}

fn run(args: &[&str]) -> Option<String> {
    let out = Command::new("git").args(args).output().ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
}
