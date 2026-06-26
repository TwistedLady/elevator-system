use std::collections::VecDeque;
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::Message;
use serde_json::Value;

use super::app::{ElevatorState, HealthComp, HealthSnapshot, LogSource, MAX_LOG_LINES};
use crate::BoxErr;

// `offset_reset` is "latest" or "earliest". The live monitor passes "latest": its trend stamps
// each state with console ARRIVAL time, so replaying the whole topic from "earliest" (which a
// fresh, non-committing group does on every (re)subscribe — e.g. after the state topic is
// recreated by a ConfigMap-switch test) dumps thousands of old states at ~the same x and
// collapses each elevator's line into a vertical streak. "earliest" stays for selftest/watch,
// which want the full backlog.
pub fn spawn_consumer(brokers: String, topic: String, offset_reset: String, tx: Sender<ElevatorState>) {
    std::thread::spawn(move || loop {
        match consume_into(&brokers, &topic, &offset_reset, &tx) {
            Ok(()) => break,
            Err(_) => std::thread::sleep(Duration::from_secs(2)),
        }
    });
}

fn consume_into(
    brokers: &str,
    topic: &str,
    offset_reset: &str,
    tx: &Sender<ElevatorState>,
) -> Result<(), BoxErr> {
    let group = format!("elevator-console-{}", std::process::id());
    let consumer: BaseConsumer = ClientConfig::new()
        .set("bootstrap.servers", brokers)
        .set("group.id", &group)
        .set("auto.offset.reset", offset_reset)
        .set("enable.auto.commit", "false")
        .create()?;
    consumer.subscribe(&[topic])?;

    loop {
        match consumer.poll(Duration::from_millis(500)) {
            None => {}
            Some(Ok(msg)) => {
                if let Some(payload) = msg.payload() {
                    if let Ok(state) = serde_json::from_slice::<ElevatorState>(payload) {
                        if tx.send(state).is_err() {
                            return Ok(());
                        }
                    }
                }
            }
            Some(Err(_)) => {}
        }
    }
}

pub fn spawn_health_poll(health_url: String, shared: Arc<Mutex<HealthSnapshot>>) {
    std::thread::spawn(move || {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(2))
            .timeout_read(Duration::from_secs(2))
            .build();
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
    HealthSnapshot { reachable: true, overall, components }
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
