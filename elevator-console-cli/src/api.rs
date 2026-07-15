//! Blocking HTTP client for the elevator API — the console's only path to the system (never Kafka).
//! TLS-only: verifies the api against the bundled self-signed CA.
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::BoxErr;

#[derive(Debug, Clone, Default, Deserialize)]
pub struct ElevatorConfig {
    #[serde(rename = "maxFloor")]
    pub max_floor: i32,
    pub elevators: Vec<String>,
}

impl ElevatorConfig {
    pub fn is_known(&self) -> bool {
        self.max_floor > 0 && !self.elevators.is_empty()
    }

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

pub fn get_config(agent: &ureq::Agent, api_base: &str) -> Result<ElevatorConfig, BoxErr> {
    let body = agent.get(&config_url(api_base)).call()?.into_string()?;
    Ok(serde_json::from_str(&body)?)
}

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
    #[serde(rename = "passengerId", skip_serializing_if = "Option::is_none")]
    passenger_id: Option<&'a str>,
}

pub fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(3))
        .tls_config(tls_config())
        .build()
}

pub fn health_url(api_base: &str) -> String {
    format!("{api_base}/actuator/health")
}

#[derive(Deserialize)]
struct VersionResp {
    version: String,
}

pub fn get_version(agent: &ureq::Agent, api_base: &str) -> Result<String, BoxErr> {
    let url = format!("{api_base}/api/version");
    let body = agent.get(&url).call()?.into_string()?;
    let resp: VersionResp = serde_json::from_str(&body)?;
    Ok(resp.version)
}

pub fn post_call(
    agent: &ureq::Agent,
    api_base: &str,
    elevator: &str,
    floor: i32,
    id: &str,
    passenger: Option<&str>,
) -> Result<(), BoxErr> {
    let url = format!("{api_base}/api/call");
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

#[derive(Debug, Clone, Deserialize)]
pub struct SimulateResponse {
    #[serde(rename = "runId")]
    pub run_id: String,
    pub count: u64,
    pub ids: Vec<String>,
}

pub fn post_simulate(agent: &ureq::Agent, api_base: &str) -> Result<SimulateResponse, BoxErr> {
    let url = format!("{api_base}/api/simulate");
    let body = agent.post(&url).call()?.into_string()?;
    Ok(serde_json::from_str(&body)?)
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct SimProgress {
    pub calls: u64,
    pub orders: u64,
    #[serde(rename = "doneCalls")]
    pub done_calls: u64,
    #[serde(rename = "firstCall")]
    pub first_call: Option<String>,
    #[serde(rename = "lastDone")]
    pub last_done: Option<String>,
}

pub fn get_progress(
    agent: &ureq::Agent,
    api_base: &str,
    run_id: &str,
    size: u64,
) -> Result<SimProgress, BoxErr> {
    let url = format!("{api_base}/api/simulate/progress?runId={run_id}&size={size}");
    let body = agent.get(&url).call()?.into_string()?;
    Ok(serde_json::from_str(&body)?)
}
