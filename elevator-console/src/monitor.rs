//! `monitor` subcommand: tail `elevator-state` and render a live table.

use std::collections::BTreeMap;
use std::io::Write;
use std::time::Duration;

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::Message;
use serde::Deserialize;

use crate::BoxErr;

/// Mirrors `ElevatorStateDto` published by elevator-app. `tag` is ignored on purpose.
#[derive(Debug, Clone, Deserialize)]
struct ElevatorState {
    #[serde(rename = "elevatorName")]
    elevator_name: String,
    direction: String,
    motion: String,
    floor: i32,
}

pub fn run(brokers: &str, topic: &str) -> Result<(), BoxErr> {
    // Fresh group per run + earliest => replay the whole topic and rebuild current state.
    let group = format!("elevator-console-{}", std::process::id());

    let consumer: BaseConsumer = ClientConfig::new()
        .set("bootstrap.servers", brokers)
        .set("group.id", &group)
        .set("auto.offset.reset", "earliest")
        .set("enable.auto.commit", "false")
        .create()?;

    consumer.subscribe(&[topic])?;
    eprintln!("Monitoring '{topic}' at {brokers} (Ctrl-C to quit)…");

    let mut latest: BTreeMap<String, ElevatorState> = BTreeMap::new();
    loop {
        match consumer.poll(Duration::from_millis(500)) {
            None => {} // nothing this tick
            Some(Ok(msg)) => {
                if let Some(payload) = msg.payload() {
                    match serde_json::from_slice::<ElevatorState>(payload) {
                        Ok(state) => {
                            latest.insert(state.elevator_name.clone(), state);
                            render(&latest);
                        }
                        Err(e) => eprintln!("skipping unparseable message: {e}"),
                    }
                }
            }
            Some(Err(e)) => eprintln!("kafka error: {e}"),
        }
    }
}

/// Clear the screen and redraw the whole table — simple and flicker-free enough for a demo.
fn render(latest: &BTreeMap<String, ElevatorState>) {
    print!("\x1b[2J\x1b[H"); // clear screen + cursor home
    println!("🛗  Elevator monitor — {} elevator(s)\n", latest.len());
    println!("{:<16} {:>6}  {:<10} {:<8}", "ELEVATOR", "FLOOR", "DIRECTION", "MOTION");
    println!("{}", "-".repeat(46));
    for s in latest.values() {
        println!(
            "{:<16} {:>6}  {:<10} {:<8}",
            s.elevator_name, s.floor, s.direction, s.motion
        );
    }
    std::io::stdout().flush().ok();
}
