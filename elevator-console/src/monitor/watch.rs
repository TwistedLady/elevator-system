//! Headless live view: stream elevator states to stdout (no TUI).
//!
//! Reuses the same Kafka consumer as the TUI ([`super::sources::spawn_consumer`]), but
//! prints a compact text block each refresh instead of taking over the screen. This runs
//! in ANY shell — including the in-session `!` bash, where the full-screen `monitor` can't
//! initialize a terminal.

use std::collections::BTreeMap;
use std::sync::mpsc;
use std::time::{Duration, Instant};

use super::app::ElevatorState;
use super::sources;
use crate::BoxErr;

pub fn run_watch(
    brokers: &str,
    state_topic: &str,
    refresh_ms: u64,
    duration_secs: Option<u64>,
) -> Result<(), BoxErr> {
    let (tx, rx) = mpsc::channel::<ElevatorState>();
    sources::spawn_consumer(brokers.to_string(), state_topic.to_string(), tx);

    println!("watching '{state_topic}' on {brokers} (Ctrl-C to stop)…");
    let start = Instant::now();
    // Keyed by natural order (e2 before e10), so the block is stable and sorted.
    let mut latest: BTreeMap<(String, u64), ElevatorState> = BTreeMap::new();
    loop {
        while let Ok(s) = rx.try_recv() {
            latest.insert(natural_key(&s.elevator_name), s);
        }
        let secs = start.elapsed().as_secs();
        println!("── t+{secs}s  ({} elevators) ──────────────", latest.len());
        for s in latest.values() {
            println!("  {:<8} floor {:>3}  {:<5} {}", s.elevator_name, s.floor, s.direction, s.motion);
        }
        std::thread::sleep(Duration::from_millis(refresh_ms));
        if let Some(d) = duration_secs {
            if start.elapsed().as_secs() >= d {
                break;
            }
        }
    }
    Ok(())
}

/// Natural-order key: non-digit prefix + trailing number, so "e2" sorts before "e10".
fn natural_key(name: &str) -> (String, u64) {
    match name.find(|c: char| c.is_ascii_digit()) {
        Some(i) => {
            let (prefix, num) = name.split_at(i);
            (prefix.to_string(), num.parse::<u64>().unwrap_or(u64::MAX))
        }
        None => (name.to_string(), u64::MAX),
    }
}
