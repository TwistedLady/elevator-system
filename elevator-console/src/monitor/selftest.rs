use std::collections::BTreeSet;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::Path;
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use super::app::{ElevatorState, HealthSnapshot};
use super::sources;
use crate::BoxErr;

pub fn run_selftest(
    brokers: &str,
    state_topic: &str,
    health_url: &str,
    duration_secs: u64,
    log_path: &str,
) -> Result<(), BoxErr> {
    let mut log = Log::create(log_path)?;
    log.line(&format!(
        "started: brokers={brokers} state-topic={state_topic} health-url={health_url} window={duration_secs}s"
    ));

    let health = Arc::new(Mutex::new(HealthSnapshot::default()));
    sources::spawn_health_poll(health_url.to_string(), Arc::clone(&health));

    let (tx, rx) = mpsc::channel::<ElevatorState>();
    sources::spawn_consumer(brokers.to_string(), state_topic.to_string(), "earliest".to_string(), tx);

    let deadline = Instant::now() + Duration::from_secs(duration_secs);
    let mut count = 0u64;
    let mut elevators: BTreeSet<String> = BTreeSet::new();
    let mut sample: Option<ElevatorState> = None;
    while Instant::now() < deadline {
        while let Ok(s) = rx.try_recv() {
            count += 1;
            elevators.insert(s.elevator_name.clone());
            sample = Some(s);
        }
        std::thread::sleep(Duration::from_millis(100));
    }

    let h = health.lock().map(|g| g.clone()).unwrap_or_default();
    let comps = h
        .components
        .iter()
        .map(|c| format!("{}={}", c.name, c.status))
        .collect::<Vec<_>>()
        .join(",");
    log.line(&format!("health: reachable={} overall={} [{}]", h.reachable, h.overall, comps));
    for c in h.components.iter().filter(|c| c.status != "UP") {
        log.line(&format!("  DOWN: {} -> {} ({})", c.name, c.status, c.detail));
    }

    let names: Vec<String> = elevators.iter().cloned().collect();
    log.line(&format!(
        "kafka: consumed {count} states; distinct elevators={} [{}]",
        names.len(),
        names.join(",")
    ));
    if let Some(s) = &sample {
        log.line(&format!(
            "  sample: {} floor={} dir={} motion={}",
            s.elevator_name, s.floor, s.direction, s.motion
        ));
    }

    let api_ok = h.reachable && h.overall == "UP";
    let kafka_ok = count > 0;
    if api_ok && kafka_ok {
        log.line("RESULT: PASS (api UP, kafka consuming)");
        Ok(())
    } else {
        let mut reasons = Vec::new();
        if !h.reachable {
            reasons.push("api unreachable".to_string());
        } else if h.overall != "UP" {
            reasons.push(format!("api health {}", h.overall));
        }
        if !kafka_ok {
            reasons.push("no kafka states consumed".to_string());
        }
        let msg = format!("RESULT: FAIL ({})", reasons.join("; "));
        log.line(&msg);
        Err(msg.into())
    }
}

struct Log {
    file: std::fs::File,
}

impl Log {
    fn create(path: &str) -> Result<Self, BoxErr> {
        if let Some(parent) = Path::new(path).parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(path)
            .map_err(|e| format!("cannot open report file '{path}': {e}"))?;
        Ok(Self { file })
    }

    fn line(&mut self, msg: &str) {
        let line = format!("[console-selftest] {msg}");
        println!("{line}");
        let _ = writeln!(self.file, "{line}");
    }
}
