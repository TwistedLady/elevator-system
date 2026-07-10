//! Background HTTP source threads for the monitor: each prefers the api's SSE stream and falls back
//! to polling, feeding state/door/config/version/health into channels or shared mutexes.
use std::io::{BufRead, BufReader};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::Value;

use super::app::{DoorState, ElevatorState, HealthComp, HealthSnapshot};

#[derive(PartialEq)]
enum Loop {
    Stop,
    Continue,
}

pub fn spawn_state_source(api_base: String, tx: Sender<ElevatorState>) {
    std::thread::spawn(move || loop {
        if stream_states(&api_base, &tx) == Loop::Stop {
            return;
        }
        if poll_states(&api_base, &tx, Duration::from_secs(10)) == Loop::Stop {
            return;
        }
    });
}

fn poll_states(api_base: &str, tx: &Sender<ElevatorState>, window: Duration) -> Loop {
    let agent = crate::api::agent();
    let url = format!("{api_base}/api/elevator");
    let deadline = Instant::now() + window;
    while Instant::now() < deadline {
        if let Ok(resp) = agent.get(&url).call() {
            if let Ok(body) = resp.into_string() {
                if let Ok(states) = serde_json::from_str::<Vec<ElevatorState>>(&body) {
                    for s in states {
                        if tx.send(s).is_err() {
                            return Loop::Stop;
                        }
                    }
                }
            }
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    Loop::Continue
}

fn stream_states(api_base: &str, tx: &Sender<ElevatorState>) -> Loop {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(30))
        .tls_config(crate::api::tls_config())
        .build();
    let url = format!("{api_base}/api/elevator/stream");
    let resp = match agent.get(&url).set("Accept", "text/event-stream").call() {
        Ok(r) => r,
        Err(_) => return Loop::Continue,
    };
    let reader = BufReader::new(resp.into_reader());
    for line in reader.lines() {
        let Ok(line) = line else {
            return Loop::Continue;
        };
        if let Some(data) = line.strip_prefix("data:") {
            let data = data.trim();
            if data.is_empty() {
                continue;
            }
            if let Ok(state) = serde_json::from_str::<ElevatorState>(data) {
                if tx.send(state).is_err() {
                    return Loop::Stop;
                }
            }
        }
    }
    Loop::Continue
}

pub fn spawn_door_source(api_base: String, tx: Sender<DoorState>) {
    std::thread::spawn(move || loop {
        if stream_doors(&api_base, &tx) == Loop::Stop {
            return;
        }
        if poll_doors(&api_base, &tx, Duration::from_secs(10)) == Loop::Stop {
            return;
        }
    });
}

fn poll_doors(api_base: &str, tx: &Sender<DoorState>, window: Duration) -> Loop {
    let agent = crate::api::agent();
    let url = format!("{api_base}/api/door");
    let deadline = Instant::now() + window;
    while Instant::now() < deadline {
        if let Ok(resp) = agent.get(&url).call() {
            if let Ok(body) = resp.into_string() {
                if let Ok(states) = serde_json::from_str::<Vec<DoorState>>(&body) {
                    for s in states {
                        if tx.send(s).is_err() {
                            return Loop::Stop;
                        }
                    }
                }
            }
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    Loop::Continue
}

fn stream_doors(api_base: &str, tx: &Sender<DoorState>) -> Loop {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(30))
        .tls_config(crate::api::tls_config())
        .build();
    let url = format!("{api_base}/api/door/stream");
    let resp = match agent.get(&url).set("Accept", "text/event-stream").call() {
        Ok(r) => r,
        Err(_) => return Loop::Continue,
    };
    let reader = BufReader::new(resp.into_reader());
    for line in reader.lines() {
        let Ok(line) = line else {
            return Loop::Continue;
        };
        if let Some(data) = line.strip_prefix("data:") {
            let data = data.trim();
            if data.is_empty() {
                continue;
            }
            if let Ok(state) = serde_json::from_str::<DoorState>(data) {
                if tx.send(state).is_err() {
                    return Loop::Stop;
                }
            }
        }
    }
    Loop::Continue
}

pub fn spawn_config_poll(api_base: String, shared: Arc<Mutex<crate::api::ElevatorConfig>>) {
    std::thread::spawn(move || {
        let agent = crate::api::agent();
        loop {
            if let Ok(cfg) = crate::api::get_config(&agent, &api_base) {
                if let Ok(mut c) = shared.lock() {
                    *c = cfg;
                }
            }
            std::thread::sleep(Duration::from_secs(5));
        }
    });
}

pub fn spawn_version_poll(api_base: String, shared: Arc<Mutex<Option<String>>>) {
    std::thread::spawn(move || {
        let agent = crate::api::agent();
        loop {
            if let Ok(v) = crate::api::get_version(&agent, &api_base) {
                if let Ok(mut g) = shared.lock() {
                    *g = Some(v);
                }
            }
            std::thread::sleep(Duration::from_secs(5));
        }
    });
}

pub fn spawn_health_poll(health_url: String, shared: Arc<Mutex<HealthSnapshot>>) {
    std::thread::spawn(move || {
        let agent = crate::api::agent();
        loop {
            let snapshot = match agent.get(&health_url).call() {
                Ok(resp) => parse_health(&resp.into_string().unwrap_or_default()),
                Err(ureq::Error::Status(_, resp)) => {
                    parse_health(&resp.into_string().unwrap_or_default())
                }
                Err(_) => HealthSnapshot {
                    reachable: false,
                    overall: "DOWN".to_string(),
                    components: Vec::new(),
                },
            };
            if let Ok(mut h) = shared.lock() {
                *h = snapshot;
            }
            std::thread::sleep(Duration::from_secs(3));
        }
    });
}

fn parse_health(body: &str) -> HealthSnapshot {
    let v: Value = serde_json::from_str(body).unwrap_or(Value::Null);
    let overall = v["status"].as_str().unwrap_or("?").to_string();
    let mut components = Vec::new();
    if let Some(obj) = v["components"].as_object() {
        for (name, comp) in obj {
            components.push(HealthComp {
                name: name.clone(),
                status: comp["status"].as_str().unwrap_or("?").to_string(),
                detail: summarize(&comp["details"]),
            });
        }
    }
    HealthSnapshot {
        reachable: true,
        overall,
        components,
    }
}

fn summarize(details: &Value) -> String {
    match details.as_object() {
        Some(obj) => obj
            .iter()
            .take(2)
            .map(|(k, v)| format!("{k}={}", compact(v)))
            .collect::<Vec<_>>()
            .join("  "),
        None => String::new(),
    }
}

fn compact(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_health_reads_overall_and_components() {
        let body = r#"{
            "status":"UP",
            "components":{
                "kafka":{"status":"UP","details":{"cluster":"local","nodes":3}},
                "db":{"status":"DOWN","details":{"error":"timeout"}}
            }
        }"#;
        let h = parse_health(body);
        assert!(h.reachable);
        assert_eq!(h.overall, "UP");
        let db = h.components.iter().find(|c| c.name == "db").unwrap();
        assert_eq!(db.status, "DOWN");
        assert!(db.detail.contains("error=timeout"));
    }

    #[test]
    fn parse_health_survives_garbage() {
        let h = parse_health("not json");
        assert_eq!(h.overall, "?");
        assert!(h.components.is_empty());
    }

    #[test]
    fn compact_unquotes_strings_but_keeps_numbers() {
        assert_eq!(compact(&Value::String("local".into())), "local");
        assert_eq!(compact(&serde_json::json!(3)), "3");
    }
}
