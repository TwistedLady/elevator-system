//! Thin blocking HTTP client for the elevator API. The console reaches the system ONLY through
//! this API (never Kafka directly): orders are POSTed, state is polled/streamed, order status and
//! health are GETed. No TLS, no async — just `ureq`.
use std::sync::Arc;
use std::time::Duration;

use serde::Serialize;

use crate::BoxErr;

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
struct OrderPayload<'a> {
    tag: &'a str,
    #[serde(rename = "elevatorName")]
    elevator_name: &'a str,
    floor: i32,
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

/// POST an order. The API validates it and publishes it to the system; we don't wait for the car.
/// Fire-and-forget from the caller's view (success = the API accepted it).
pub fn post_order(
    agent: &ureq::Agent,
    api_base: &str,
    elevator: &str,
    floor: i32,
    tag: &str,
) -> Result<(), BoxErr> {
    let url = format!("{api_base}/api/order");
    let body = serde_json::to_string(&OrderPayload {
        tag,
        elevator_name: elevator,
        floor,
    })?;
    match agent
        .post(&url)
        .set("Content-Type", "application/json")
        .send_string(&body)
    {
        Ok(_) => Ok(()),
        Err(ureq::Error::Status(code, _)) => Err(format!("POST /api/order → HTTP {code}").into()),
        Err(e) => Err(e.into()),
    }
}

/// POST with a few retries, for the bulk simulator where transient blips shouldn't drop an order.
pub fn post_order_retry(
    agent: &ureq::Agent,
    api_base: &str,
    elevator: &str,
    floor: i32,
    tag: &str,
) -> Result<(), BoxErr> {
    let mut last = None;
    for attempt in 0..3 {
        match post_order(agent, api_base, elevator, floor, tag) {
            Ok(()) => return Ok(()),
            Err(e) => {
                last = Some(e);
                std::thread::sleep(Duration::from_millis(50 * (attempt + 1)));
            }
        }
    }
    Err(last.unwrap_or_else(|| "post_order failed".into()))
}
