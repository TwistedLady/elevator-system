use std::process::Command;
use std::sync::{Arc, Mutex};
use std::time::Duration;

const DEPLOY: &str = "elevator-app";
const CONFIGMAP: &str = "elevator-config";
const ENGINE_KEY: &str = "ELEVATOR_ENGINE";

#[derive(Clone)]
pub struct PodLine {
    pub name: String,
    pub status: String,
    pub ready: String,
    pub restarts: String,
}

#[derive(Clone)]
pub struct K8sSnapshot {
    pub reachable: bool,
    pub mode: String,
    pub pods: Vec<PodLine>,
    pub note: String,
}

impl Default for K8sSnapshot {
    fn default() -> Self {
        Self {
            reachable: false,
            mode: "?".into(),
            pods: Vec::new(),
            note: "querying kubectl…".into(),
        }
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

fn read_mode() -> Result<String, String> {
    let value = kubectl(&[
        "get",
        "configmap",
        CONFIGMAP,
        "-o",
        &format!("jsonpath={{.data.{ENGINE_KEY}}}"),
    ])?;
    let mode = value.trim();
    if mode.is_empty() {
        Ok("?".into())
    } else {
        Ok(mode.to_string())
    }
}

fn read_pods() -> Result<Vec<PodLine>, String> {
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

pub fn spawn_k8s_poll(shared: Arc<Mutex<K8sSnapshot>>) {
    std::thread::spawn(move || loop {
        let snap = match (read_mode(), read_pods()) {
            (Ok(mode), Ok(pods)) => K8sSnapshot {
                reachable: true,
                mode,
                pods,
                note: String::new(),
            },
            (Err(e), _) | (_, Err(e)) => K8sSnapshot {
                reachable: false,
                mode: "?".into(),
                pods: Vec::new(),
                note: e,
            },
        };
        if let Ok(mut s) = shared.lock() {
            *s = snap;
        }
        std::thread::sleep(Duration::from_secs(3));
    });
}

pub fn restart() -> Result<String, String> {
    kubectl(&["rollout", "restart", &format!("deployment/{DEPLOY}")])?;
    Ok("rolling restart of elevator-app…".into())
}

pub fn set_mode(mode: &str) -> Result<String, String> {
    if mode != "fast" && mode != "slow" {
        return Err("unknown mode".into());
    }
    let patch = format!(r#"{{"data":{{"{ENGINE_KEY}":"{mode}"}}}}"#);
    kubectl(&[
        "patch",
        "configmap",
        CONFIGMAP,
        "--type=merge",
        "-p",
        &patch,
    ])?;
    Ok(format!(
        "engine set to {mode} — app hot-reloads in a few seconds (no restart)"
    ))
}
