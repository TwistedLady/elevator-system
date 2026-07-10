//! Thin blocking HTTP client for the elevator API. The console reaches the system ONLY through
//! this API (never Kafka directly): calls are POSTed, state is polled/streamed, call status and
//! health are GETed. No TLS, no async — just `ureq`.
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::BoxErr;

/// The system's live limits, fetched from the API (`GET /api/config`) — never hardcoded here.
/// `max_floor` = highest valid floor (0..max_floor); `elevators` = the allowed fleet.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct ElevatorConfig {
    #[serde(rename = "maxFloor")]
    pub max_floor: i32,
    pub elevators: Vec<String>,
}

impl ElevatorConfig {
    /// True once real values have been fetched (max_floor > 0 and a non-empty fleet).
    pub fn is_known(&self) -> bool {
        self.max_floor > 0 && !self.elevators.is_empty()
    }

    /// Friendly client-side pre-check against the fetched limits. The API stays authoritative;
    /// when the config is unknown (API unreachable) this passes and lets the API decide.
    pub fn validate_call(&self, elevator: &str, floor: i32) -> Result<(), String> {
        if !self.is_known() {
            return Ok(());
        }
        if floor < 0 || floor > self.max_floor {
            return Err(format!("floor must be between 0 and {}", self.max_floor));
        }
        if !self.elevators.iter().any(|e| e == elevator) {
            return Err(format!(
                "unknown elevator '{elevator}' (allowed: {})",
                self.elevators.join(", ")
            ));
        }
        Ok(())
    }
}

pub fn config_url(api_base: &str) -> String {
    format!("{api_base}/api/config")
}

/// Fetch the live limits from the API. Callers decide how to degrade if it fails.
pub fn get_config(agent: &ureq::Agent, api_base: &str) -> Result<ElevatorConfig, BoxErr> {
    let body = agent.get(&config_url(api_base)).call()?.into_string()?;
    Ok(serde_json::from_str(&body)?)
}

/// A rustls config that trusts the bundled elevator-api certificate (self-signed). The console
/// speaks TLS to the api and verifies it against this cert — plain HTTP is not accepted.
pub fn tls_config() -> Arc<rustls::ClientConfig> {
    let mut roots = rustls::RootCertStore::empty();
    let pem: &[u8] = include_bytes!("../certs/elevator-ca.crt");
    let mut reader = std::io::BufReader::new(pem);
    for cert in rustls_pemfile::certs(&mut reader).flatten() {
        let _ = roots.add(cert);
    }
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_safe_default_protocol_versions()
    .expect("rustls default protocol versions")
    .with_root_certificates(roots)
    .with_no_client_auth();
    Arc::new(config)
}

#[derive(Serialize)]
struct CallPayload<'a> {
    id: &'a str,
    #[serde(rename = "elevatorName")]
    elevator_name: &'a str,
    floor: i32,
    // Optional passenger identity, sent in the body; omitted (anonymous) when None.
    #[serde(rename = "passengerId", skip_serializing_if = "Option::is_none")]
    passenger_id: Option<&'a str>,
}

/// A short-timeout agent for request/response calls (orders, status, health).
pub fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(3))
        .tls_config(tls_config())
        .build()
}

/// The API base URL, e.g. `http://localhost:8080`. Endpoints hang off it.
pub fn health_url(api_base: &str) -> String {
    format!("{api_base}/actuator/health")
}

#[derive(Deserialize)]
struct VersionResp {
    version: String,
}

/// GET /api/version — the backend's build version. Used to check the console matches the backend.
/// Parsed via serde_json (ureq's `json` feature is off) to keep the dependency surface small.
pub fn get_version(agent: &ureq::Agent, api_base: &str) -> Result<String, BoxErr> {
    let url = format!("{api_base}/api/version");
    let body = agent.get(&url).call()?.into_string()?;
    let resp: VersionResp = serde_json::from_str(&body)?;
    Ok(resp.version)
}

/// POST a call. The API validates it and publishes it to the system; we don't wait for the car.
/// Fire-and-forget from the caller's view (success = the API accepted it).
pub fn post_call(
    agent: &ureq::Agent,
    api_base: &str,
    elevator: &str,
    floor: i32,
    id: &str,
    passenger: Option<&str>,
) -> Result<(), BoxErr> {
    let url = format!("{api_base}/api/call");
    // The passenger identity travels in the body; None => anonymous call.
    let body = serde_json::to_string(&CallPayload {
        id,
        elevator_name: elevator,
        floor,
        passenger_id: passenger,
    })?;
    let req = agent.post(&url).set("Content-Type", "application/json");
    match req.send_string(&body) {
        Ok(_) => Ok(()),
        Err(ureq::Error::Status(code, _)) => Err(format!("POST /api/call → HTTP {code}").into()),
        Err(e) => Err(e.into()),
    }
}

/// The server-side simulation is triggered here: `POST /api/simulate?count=N` fires N calls in the
/// api and returns the run id plus the generated call ids to poll.
#[derive(Debug, Clone, Deserialize)]
pub struct SimulateResponse {
    #[serde(rename = "runId")]
    pub run_id: String,
    pub count: u64,
    pub ids: Vec<String>,
}

/// A batched status roll-up for a set of simulated calls: `done + progress + pending == total`.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct SimStatus {
    pub total: u64,
    pub done: u64,
    pub progress: u64,
    pub pending: u64,
}

/// Kick off a server-side simulation of `count` calls. The api caps and defaults the count itself.
pub fn post_simulate(
    agent: &ureq::Agent,
    api_base: &str,
    count: u64,
) -> Result<SimulateResponse, BoxErr> {
    let url = format!("{api_base}/api/simulate?count={count}");
    let body = agent.post(&url).call()?.into_string()?;
    Ok(serde_json::from_str(&body)?)
}

#[derive(Serialize)]
struct IdsPayload<'a> {
    ids: &'a [String],
}

/// Poll the aggregate status of a running simulation by its call ids.
pub fn post_simulate_status(
    agent: &ureq::Agent,
    api_base: &str,
    ids: &[String],
) -> Result<SimStatus, BoxErr> {
    let url = format!("{api_base}/api/simulate/status");
    let payload = serde_json::to_string(&IdsPayload { ids })?;
    let body = agent
        .post(&url)
        .set("Content-Type", "application/json")
        .send_string(&payload)?
        .into_string()?;
    Ok(serde_json::from_str(&body)?)
}
