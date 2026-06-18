//! `monitor` subcommand: live building chart + inline ordering.
//!
//! Resilient: if the broker goes away (backend restart) the consumer is rebuilt and
//! we keep going — the monitor never exits on a connection blip.
//!
//! Ordering: a background thread reads "<elevator> <floor>" lines from stdin and sends
//! the order, while the main thread keeps consuming state and drawing the chart.

use std::collections::{BTreeMap, BTreeSet};
use std::io::{BufRead, Write};
use std::sync::{Arc, Mutex};
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

/// Floors used by the `sim` command when generating random orders.
const SIM_MAX_FLOOR: i32 = 15;

pub fn run(brokers: &str, state_topic: &str, command_topic: &str) -> Result<(), BoxErr> {
    // Elevator names seen so far, shared with the input thread so `sim` targets real cars.
    let known: Arc<Mutex<BTreeSet<String>>> = Arc::new(Mutex::new(BTreeSet::new()));

    // Read orders/commands from the keyboard in the background so the chart keeps refreshing.
    spawn_order_input(brokers.to_string(), command_topic.to_string(), Arc::clone(&known));

    // State survives reconnects, so the chart stays on screen during an outage.
    let mut latest: BTreeMap<String, ElevatorState> = BTreeMap::new();
    render(&latest, state_topic, brokers);

    // Outer loop = reconnect loop; inner consume_loop only returns on setup failure.
    loop {
        if let Err(e) = consume_loop(brokers, state_topic, &mut latest, &known) {
            eprintln!("monitor: connection issue ({e}); retrying in 2s…");
            std::thread::sleep(Duration::from_secs(2));
        }
    }
}

/// Background reader. Recognises two commands:
///   `<elevator> <floor>`  → one order
///   `sim <count>`         → `<count>` random orders across the known elevators
fn spawn_order_input(
    brokers: String,
    command_topic: String,
    known: Arc<Mutex<BTreeSet<String>>>,
) {
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        for line in stdin.lock().lines() {
            let line = match line {
                Ok(l) => l,
                Err(_) => break, // stdin closed
            };
            let parts: Vec<&str> = line.split_whitespace().collect();
            match parts.as_slice() {
                [] => {}
                [cmd, n] if cmd.eq_ignore_ascii_case("sim") => match n.parse::<u64>() {
                    Ok(count) => {
                        let elevators = elevator_pool(&known);
                        let threads = count.clamp(1, 4);
                        if let Err(e) = crate::sender::simulate(
                            &brokers,
                            &command_topic,
                            count,
                            threads,
                            &elevators,
                            SIM_MAX_FLOOR,
                        ) {
                            eprintln!("sim failed: {e}");
                        }
                    }
                    Err(_) => eprintln!("sim usage: sim <count>   e.g.  sim 60"),
                },
                [elevator, floor] => match floor.parse::<i32>() {
                    Ok(f) => {
                        if let Err(e) = crate::sender::send_one(&brokers, &command_topic, elevator, f)
                        {
                            eprintln!("order failed: {e}");
                        }
                    }
                    Err(_) => eprintln!("floor must be a number"),
                },
                _ => eprintln!("type:  <elevator> <floor>   or   sim <count>"),
            }
        }
    });
}

/// Elevators for `sim` to target: those seen so far, or a default 8 if none yet.
fn elevator_pool(known: &Arc<Mutex<BTreeSet<String>>>) -> Vec<String> {
    let set = known.lock().unwrap_or_else(|e| e.into_inner());
    if set.is_empty() {
        (1..=8).map(|i| format!("lift-{i:02}")).collect()
    } else {
        set.iter().cloned().collect()
    }
}

fn consume_loop(
    brokers: &str,
    topic: &str,
    latest: &mut BTreeMap<String, ElevatorState>,
    known: &Arc<Mutex<BTreeSet<String>>>,
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
                        if let Ok(mut k) = known.lock() {
                            k.insert(state.elevator_name.clone());
                        }
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

    println!("\norder ▸  <elevator> <floor>   |   sim <count>   |   Enter   (e.g.  lift-01 7  ·  sim 60)");
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
