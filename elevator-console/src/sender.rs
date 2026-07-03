use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use rand::Rng;
use rdkafka::config::ClientConfig;
use rdkafka::error::{KafkaError, RDKafkaErrorCode};
use rdkafka::producer::{BaseProducer, BaseRecord, Producer};
use serde::Serialize;

use crate::BoxErr;

#[derive(Serialize)]
struct ElevatorOrder<'a> {
    tag: &'a str,
    #[serde(rename = "elevatorName")]
    elevator_name: &'a str,
    floor: i32,
}

fn make_producer(brokers: &str) -> Result<BaseProducer, KafkaError> {
    ClientConfig::new()
        .set("bootstrap.servers", brokers)
        .create()
}

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
    let mut record = BaseRecord::to(topic).key(elevator).payload(&payload);
    loop {
        match producer.send(record) {
            Ok(()) => return Ok(()),
            Err((KafkaError::MessageProduction(RDKafkaErrorCode::QueueFull), rejected)) => {
                producer.poll(Duration::from_millis(100));
                record = rejected;
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
    Ok(())
}

pub fn sim_tag(run_id: u64, tid: u64, i: u64) -> String {
    format!("sim-{run_id}-{tid}-{i}")
}

pub fn sim_tags(run_id: u64, count: u64, threads: u64, limit: usize) -> Vec<String> {
    let threads = threads.max(1);
    let per = count / threads;
    let rem = count % threads;
    let mut tags = Vec::new();
    for tid in 0..threads {
        let n = per + if tid < rem { 1 } else { 0 };
        for i in 0..n {
            if tags.len() >= limit {
                return tags;
            }
            tags.push(sim_tag(run_id, tid, i));
        }
    }
    tags
}

#[allow(clippy::too_many_arguments)]
pub fn run_simulation(
    brokers: &str,
    topic: &str,
    count: u64,
    threads: u64,
    elevators: &[String],
    max_floor: i32,
    sent: &AtomicU64,
    pace: Option<Duration>,
    run_id: u64,
) -> Result<(), BoxErr> {
    if elevators.is_empty() {
        return Err("need at least one elevator".into());
    }
    let threads = threads.max(1);
    let per = count / threads;
    let rem = count % threads;

    std::thread::scope(|s| -> Result<(), BoxErr> {
        let mut handles = Vec::new();
        for t in 0..threads {
            let n = per + if t < rem { 1 } else { 0 };
            handles.push(s.spawn(move || {
                worker(
                    brokers, topic, t, n, elevators, max_floor, sent, pace, run_id,
                )
            }));
        }
        for h in handles {
            h.join().expect("worker thread panicked")?;
        }
        Ok(())
    })
}

pub fn simulate(
    brokers: &str,
    topic: &str,
    count: u64,
    threads: u64,
    elevators: &[String],
    max_floor: i32,
) -> Result<(), BoxErr> {
    let sent = AtomicU64::new(0);
    println!(
        "simulating {count} orders across {} elevator(s) on {} thread(s) → '{topic}'…",
        elevators.len(),
        threads.max(1)
    );
    let start = Instant::now();

    let run_id = std::process::id() as u64;
    run_simulation(
        brokers, topic, count, threads, elevators, max_floor, &sent, None, run_id,
    )?;

    let secs = start.elapsed().as_secs_f64();
    let total = sent.load(Ordering::Relaxed);
    let rate = if secs > 0.0 { total as f64 / secs } else { 0.0 };
    println!("done: {total} orders in {secs:.2}s ({rate:.0} msg/s)");
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn worker(
    brokers: &str,
    topic: &str,
    tid: u64,
    n: u64,
    elevators: &[String],
    max_floor: i32,
    sent: &AtomicU64,
    pace: Option<Duration>,
    run_id: u64,
) -> Result<(), BoxErr> {
    let producer = make_producer(brokers)?;
    let mut rng = rand::thread_rng();
    let delay = pace
        .filter(|_| n > 0)
        .map(|p| p / n as u32)
        .filter(|d| *d >= Duration::from_millis(1));
    for i in 0..n {
        let elevator = &elevators[rng.gen_range(0..elevators.len())];
        let floor = rng.gen_range(0..=max_floor);
        let tag = sim_tag(run_id, tid, i);
        send_order(&producer, topic, elevator, floor, &tag)?;
        if i % 1000 == 0 {
            producer.poll(Duration::from_millis(0));
        }
        sent.fetch_add(1, Ordering::Relaxed);
        if let Some(d) = delay {
            std::thread::sleep(d);
        }
    }
    producer.flush(Duration::from_secs(30))?;
    Ok(())
}
