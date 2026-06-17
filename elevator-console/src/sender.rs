//! `order` and `simulate` subcommands: produce orders to the command topic.

use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use rand::Rng;
use rdkafka::config::ClientConfig;
use rdkafka::error::{KafkaError, RDKafkaErrorCode};
use rdkafka::producer::{BaseProducer, BaseRecord, Producer};
use serde::Serialize;

use crate::BoxErr;

/// Mirrors `ElevatorOrderDto` consumed by elevator-app from the command topic.
#[derive(Serialize)]
struct ElevatorOrder<'a> {
    tag: &'a str,
    #[serde(rename = "elevatorName")]
    elevator_name: &'a str,
    floor: i32,
}

fn make_producer(brokers: &str) -> Result<BaseProducer, KafkaError> {
    ClientConfig::new().set("bootstrap.servers", brokers).create()
}

/// Send one order. If librdkafka's send queue is full (it fills under bulk load),
/// poll to let it drain and retry the same record — that's the backpressure.
fn send_order(
    producer: &BaseProducer,
    topic: &str,
    elevator: &str,
    floor: i32,
    tag: &str,
) -> Result<(), BoxErr> {
    let payload = serde_json::to_string(&ElevatorOrder {
        tag,
        elevator_name: elevator,
        floor,
    })?;
    // Keyed by elevator name, same as the Spring api's producer.
    let mut record = BaseRecord::to(topic).key(elevator).payload(&payload);
    loop {
        match producer.send(record) {
            Ok(()) => return Ok(()),
            Err((KafkaError::MessageProduction(RDKafkaErrorCode::QueueFull), rejected)) => {
                producer.poll(Duration::from_millis(100));
                record = rejected; // retry the bounced record
            }
            Err((e, _)) => return Err(e.into()),
        }
    }
}

pub fn send_one(brokers: &str, topic: &str, elevator: &str, floor: i32) -> Result<(), BoxErr> {
    let producer = make_producer(brokers)?;
    let tag = format!("cli-{}", std::process::id());
    send_order(&producer, topic, elevator, floor, &tag)?;
    producer.flush(Duration::from_secs(10))?;
    println!("sent order: elevator={elevator} floor={floor} tag={tag}");
    Ok(())
}

pub fn simulate(
    brokers: &str,
    topic: &str,
    count: u64,
    threads: u64,
    elevators: &[String],
    max_floor: i32,
) -> Result<(), BoxErr> {
    if elevators.is_empty() {
        return Err("need at least one elevator".into());
    }
    let threads = threads.max(1);
    let sent = AtomicU64::new(0);
    let per = count / threads;
    let rem = count % threads;

    println!(
        "simulating {count} orders across {} elevator(s) on {threads} thread(s) → '{topic}'…",
        elevators.len()
    );
    let start = Instant::now();

    // Scoped threads can borrow `sent`, `elevators`, `brokers`, `topic` without Arc/clone.
    std::thread::scope(|s| -> Result<(), BoxErr> {
        let sent = &sent;
        let mut handles = Vec::new();
        for t in 0..threads {
            let n = per + if t < rem { 1 } else { 0 };
            handles.push(s.spawn(move || worker(brokers, topic, t, n, elevators, max_floor, sent)));
        }
        for h in handles {
            h.join().expect("worker thread panicked")?;
        }
        Ok(())
    })?;

    let secs = start.elapsed().as_secs_f64();
    let total = sent.load(Ordering::Relaxed);
    let rate = if secs > 0.0 { total as f64 / secs } else { 0.0 };
    println!("done: {total} orders in {secs:.2}s ({rate:.0} msg/s)");
    Ok(())
}

fn worker(
    brokers: &str,
    topic: &str,
    tid: u64,
    n: u64,
    elevators: &[String],
    max_floor: i32,
    sent: &AtomicU64,
) -> Result<(), BoxErr> {
    let producer = make_producer(brokers)?;
    let mut rng = rand::thread_rng();
    for i in 0..n {
        let elevator = &elevators[rng.gen_range(0..elevators.len())];
        let floor = rng.gen_range(0..=max_floor);
        let tag = format!("sim-{tid}-{i}");
        send_order(&producer, topic, elevator, floor, &tag)?;
        if i % 1000 == 0 {
            producer.poll(Duration::from_millis(0)); // serve delivery callbacks
        }
        sent.fetch_add(1, Ordering::Relaxed);
    }
    producer.flush(Duration::from_secs(30))?;
    Ok(())
}
