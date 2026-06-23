//! K8s tab data + actions. Shells out to `kubectl` (already required for the port-forwards the
//! console runs against the cluster) to read the app's current mode + pod state, and to flip the
//! mode by repointing the elevator-app deployment's envFrom at the fast/slow configmap.

use std::process::Command;
use std::sync::{Arc, Mutex};
use std::time::Duration;

const DEPLOY: &str = "elevator-app";
const CM_FAST: &str = "elevator-app-config-fast";
const CM_SLOW: &str = "elevator-app-config-slow";

#[derive(Clone)]
pub struct PodLine {
    pub name: String,
    pub status: String,
    pub ready: String,
    pub restarts: String,
}

/// Snapshot of cluster state shown in the K8s tab.
#[derive(Clone)]
pub struct K8sSnapshot {
    pub reachable: bool, // kubectl ran successfully
    pub mode: String,    // "fast" | "slow" | "?"
    pub pods: Vec<PodLine>,
    pub note: String, // error / status text when not reachable
}

impl Default for K8sSnapshot {
    fn default() -> Self {
        Self { reachable: false, mode: "?".into(), pods: Vec::new(), note: "querying kubectl…".into() }
    }
}

fn kubectl(args: &[&str]) -> Result<String, String> {
    let out = Command::new("kubectl")
        .args(args)
        .output()
        .map_err(|e| format!("kubectl not available: {e}"))?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    } else {
        Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
    }
}

/// Current mode = which configmap the deployment's first envFrom points at.
fn read_mode() -> Result<String, String> {
    let cm = kubectl(&[
        "get",
        "deployment",
        DEPLOY,
        "-o",
        "jsonpath={.spec.template.spec.containers[0].envFrom[0].configMapRef.name}",
    ])?;
    Ok(match cm.trim() {
        CM_FAST => "fast".into(),
        CM_SLOW => "slow".into(),
        other => format!("?({other})"),
    })
}

fn read_pods() -> Result<Vec<PodLine>, String> {
    // custom-columns avoids the default RESTARTS column's "6 (7h ago)" spaces breaking the split.
    let out = kubectl(&[
        "get",
        "pods",
        "--no-headers",
        "-o",
        "custom-columns=NAME:.metadata.name,STATUS:.status.phase,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount",
    ])?;
    let pods = out
        .lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split_whitespace().collect();
            (f.len() >= 4).then(|| PodLine {
                name: f[0].to_string(),
                status: f[1].to_string(),
                ready: f[2].to_string(),
                restarts: f[3].to_string(),
            })
        })
        .collect();
    Ok(pods)
}

/// Background poller: refresh the snapshot every 3s.
pub fn spawn_k8s_poll(shared: Arc<Mutex<K8sSnapshot>>) {
    std::thread::spawn(move || loop {
        let snap = match (read_mode(), read_pods()) {
            (Ok(mode), Ok(pods)) => {
                K8sSnapshot { reachable: true, mode, pods, note: String::new() }
            }
            (Err(e), _) | (_, Err(e)) => {
                K8sSnapshot { reachable: false, mode: "?".into(), pods: Vec::new(), note: e }
            }
        };
        if let Ok(mut s) = shared.lock() {
            *s = snap;
        }
        std::thread::sleep(Duration::from_secs(3));
    });
}

/// Flip the mode: repoint the deployment's envFrom configmap + rollout restart. Blocking, so call
/// off-thread. Returns a short status message.
pub fn set_mode(mode: &str) -> Result<String, String> {
    let cm = match mode {
        "fast" => CM_FAST,
        "slow" => CM_SLOW,
        _ => return Err("unknown mode".into()),
    };
    let patch = format!(
        r#"[{{"op":"replace","path":"/spec/template/spec/containers/0/envFrom/0/configMapRef/name","value":"{cm}"}}]"#
    );
    kubectl(&["patch", "deployment", DEPLOY, "--type=json", "-p", &patch])?;
    kubectl(&["rollout", "restart", &format!("deployment/{DEPLOY}")])?;
    Ok(format!("switched to {mode} — rolling the app…"))
}
