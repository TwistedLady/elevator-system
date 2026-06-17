//! `monitor` subcommand: live building chart + inline ordering.
//!
//! Resilient: if the broker goes away (backend restart) the consumer is rebuilt and
//! we keep going — the monitor never exits on a connection blip.
//!
//! Ordering: a background thread reads "<elevator> <floor>" lines from stdin and sends
//! the order, while the main thread keeps consuming state and drawing the chart.

use std::collections::BTreeMap;
use std::io::{BufRead, Write};
use std::time::{Duration, Instant};

use rdkafka::config::ClientConfig;
use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::Message;
use serde::Deserialize;

use crate::BoxErr;

/// Width of each elevator column in the chart.
const COL_W: usize = 8;

/// Mirrors `ElevatorStateDto` published by elevator-app. Only what the chart needs.
#[derive(Debug, Clone, Deserialize)]
struct ElevatorState {
    #[serde(rename = "elevatorName")]
    elevator_name: String,
    direction: String,
    motion: String,
    floor: i32,
}

pub fn run(brokers: &str, state_topic: &str, command_topic: &str) -> Result<(), BoxErr> {
    // Read orders from the keyboard in the background so the chart keeps refreshing.
    spawn_order_input(brokers.to_string(), command_topic.to_string());

    // State survives reconnects, so the chart stays on screen during an outage.
    let mut latest: BTreeMap<String, ElevatorState> = BTreeMap::new();
    render(&latest, state_topic, brokers);

    // Outer loop = reconnect loop; inner consume_loop only returns on setup failure.
    loop {
        if let Err(e) = consume_loop(brokers, state_topic, &mut latest) {
            eprintln!("monitor: connection issue ({e}); retrying in 2s…");
            std::thread::sleep(Duration::from_secs(2));
        }
    }
}

/// Background reader: each "<elevator> <floor>" line becomes an order.
fn spawn_order_input(brokers: String, command_topic: String) {
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        for line in stdin.lock().lines() {
            let line = match line {
                Ok(l) => l,
                Err(_) => break, // stdin closed
            };
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.is_empty() {
                continue;
            }
            if parts.len() != 2 {
                eprintln!("order usage: <elevator> <floor>   e.g.  lift-01 7");
                continue;
            }
            let elevator = parts[0];
            let floor: i32 = match parts[1].parse() {
                Ok(f) => f,
                Err(_) => {
                    eprintln!("floor must be a number");
                    continue;
                }
            };
            if let Err(e) = crate::sender::send_one(&brokers, &command_topic, elevator, floor) {
                eprintln!("order failed: {e}");
            }
        }
    });
}

fn consume_loop(
    brokers: &str,
    topic: &str,
    latest: &mut BTreeMap<String, ElevatorState>,
) -> Result<(), BoxErr> {
    // Fresh group per run + earliest => replay the whole topic and rebuild current state.
    let group = format!("elevator-console-{}", std::process::id());

    let consumer: BaseConsumer = ClientConfig::new()
        .set("bootstrap.servers", brokers)
        .set("group.id", &group)
        .set("auto.offset.reset", "earliest")
        .set("enable.auto.commit", "false")
        .create()?;
    consumer.subscribe(&[topic])?;

    // Throttle redraws (~8 fps) so typed input isn't wiped on every single message.
    let mut last_draw = Instant::now() - Duration::from_secs(1);
    let mut dirty = false;

    loop {
        match consumer.poll(Duration::from_millis(500)) {
            None => {} // idle tick
            Some(Ok(msg)) => {
                if let Some(payload) = msg.payload() {
                    if let Ok(state) = serde_json::from_slice::<ElevatorState>(payload) {
                        latest.insert(state.elevator_name.clone(), state);
                        dirty = true;
                    }
                    // unparseable payloads are simply ignored
                }
            }
            // Transient (broker bounce / rebalance). librdkafka reconnects on its own.
            Some(Err(_)) => {}
        }
        if dirty && last_draw.elapsed() >= Duration::from_millis(120) {
            render(latest, topic, brokers);
            last_draw = Instant::now();
            dirty = false;
        }
    }
}

/// Clear the screen and draw the building chart + an order prompt.
fn render(latest: &BTreeMap<String, ElevatorState>, topic: &str, brokers: &str) {
    print!("\x1b[2J\x1b[H"); // clear screen + cursor home

    if latest.is_empty() {
        println!("🛗  Elevator monitor — waiting for state on '{topic}' @ {brokers}…");
    } else {
        let cars: Vec<&ElevatorState> = latest.values().collect();
        let top = cars.iter().map(|c| c.floor).max().unwrap_or(0).max(0);
        let bottom = cars.iter().map(|c| c.floor).min().unwrap_or(0).min(0);

        println!(
            "🛗  Elevator monitor — {} elevator(s), {} floors\n",
            cars.len(),
            top - bottom + 1
        );

        let mut header = format!("{:>4} ", "Fl");
        for c in &cars {
            header.push_str(&center(&c.elevator_name, COL_W));
        }
        println!("{header}");

        for floor in (bottom..=top).rev() {
            let mut row = format!("{floor:>4} ");
            for c in &cars {
                let cell = if c.floor == floor {
                    format!("[{}]", car_glyph(&c.direction, &c.motion))
                } else {
                    "·".to_string() // empty shaft
                };
                row.push_str(&center(&cell, COL_W));
            }
            println!("{row}");
        }
    }

    println!("\norder ▸ type  <elevator> <floor>  then Enter   (e.g.  lift-01 7)");
    std::io::stdout().flush().ok();
}

/// Arrow for a car: direction while moving, a dot when idle/stopped.
fn car_glyph(direction: &str, motion: &str) -> char {
    if !motion.eq_ignore_ascii_case("moving") {
        return '•';
    }
    match direction.to_ascii_uppercase().as_str() {
        "UP" => '↑',
        "DOWN" => '↓',
        _ => '•',
    }
}

/// Center `s` within `w` columns (counts chars, so arrows/dots align).
fn center(s: &str, w: usize) -> String {
    let len = s.chars().count();
    if len >= w {
        return s.chars().take(w).collect();
    }
    let pad = w - len;
    let left = pad / 2;
    format!("{}{}{}", " ".repeat(left), s, " ".repeat(pad - left))
}
