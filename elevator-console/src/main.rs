use std::collections::BTreeMap;
use std::io::Write;
use std::time::Duration;

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::Message;
use serde::Deserialize;

#[derive(Debug, Clone, Deserialize)]
struct ElevatorState {
    #[serde(rename = "elevatorName")]
    elevator_name: String,
    direction: String,
    motion: String,
    floor: i32,
}

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn main() {
    let brokers = env_or("KAFKA_BOOTSTRAP", "localhost:9092");
    let topic = env_or("STATE_TOPIC", "elevator-state");
    let group = format!("elevator-console-{}", std::process::id());

    let consumer: BaseConsumer = ClientConfig::new()
        .set("bootstrap.servers", &brokers)
        .set("group.id", &group)
        .set("auto.offset.reset", "earliest")
        .set("enable.auto.commit", "false")
        .create()
        .expect("failed to create Kafka consumer");

    consumer
        .subscribe(&[&topic])
        .expect("failed to subscribe to topic");

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
