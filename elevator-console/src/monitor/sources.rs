use std::collections::VecDeque;
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::Value;

use super::app::{ElevatorState, HealthComp, HealthSnapshot, LogSource, MAX_LOG_LINES};

#[derive(PartialEq)]
enum Loop {
    Stop,     // the receiver is gone — shut the source down
    Continue, // this source ended/unavailable — try the other one
}

/// Live elevator state, entirely over HTTP (no Kafka). Prefers the API's SSE stream
/// (`GET /api/elevator/stream`, low latency); if that endpoint is absent or drops, falls back to
/// polling `GET /api/elevator` every 500ms. Either way it feeds `ElevatorState`s into `tx`, exactly
/// like the old Kafka consumer did, so the Chart/Trend views are unchanged.
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
        .build();
    let url = format!("{api_base}/api/elevator/stream");
    let resp = match agent.get(&url).set("Accept", "text/event-stream").call() {
        Ok(r) => r,
        Err(_) => return Loop::Continue, // endpoint absent / unreachable → fall back to polling
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

pub fn spawn_log_tail(source: LogSource, path: String, tx: Sender<(LogSource, String)>) {
    std::thread::spawn(move || loop {
        let Ok(file) = File::open(&path) else {
            std::thread::sleep(Duration::from_secs(1));
            continue;
        };
        let mut reader = BufReader::new(file);
        let mut line = String::new();

        let mut recent: VecDeque<String> = VecDeque::new();
        loop {
            line.clear();
            match reader.read_line(&mut line) {
                Ok(0) => break,
                Ok(_) => {
                    if recent.len() >= MAX_LOG_LINES {
                        recent.pop_front();
                    }
                    recent.push_back(line.trim_end().to_string());
                }
                Err(_) => break,
            }
        }
        for l in recent {
            if tx.send((source, l)).is_err() {
                return;
            }
        }

        loop {
            line.clear();
            match reader.read_line(&mut line) {
                Ok(0) => std::thread::sleep(Duration::from_millis(300)),
                Ok(_) => {
                    if tx.send((source, line.trim_end().to_string())).is_err() {
                        return;
                    }
                }
                Err(_) => break,
            }
        }
    });
}
