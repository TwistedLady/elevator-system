//! Order submission via the HTTP API (previously a Kafka producer). Single orders and the bulk
//! simulator both POST to `/api/order`; the API publishes them to the system.
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use rand::Rng;

use crate::api;
use crate::BoxErr;

pub fn send_one(api_base: &str, elevator: &str, floor: i32) -> Result<(), BoxErr> {
    let agent = api::agent();
    let tag = format!("cli-{}", std::process::id());
    api::post_order(&agent, api_base, elevator, floor, &tag)
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
    api_base: &str,
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
            handles.push(s.spawn(move || worker(api_base, t, n, elevators, max_floor, sent, pace, run_id)));
        }
        for h in handles {
            h.join().expect("worker thread panicked")?;
        }
        Ok(())
    })
}

pub fn simulate(
    api_base: &str,
    count: u64,
    threads: u64,
    elevators: &[String],
    max_floor: i32,
) -> Result<(), BoxErr> {
    let sent = AtomicU64::new(0);
    println!(
        "simulating {count} orders across {} elevator(s) on {} thread(s) → {api_base}…",
        elevators.len(),
        threads.max(1)
    );
    let start = Instant::now();
    let run_id = std::process::id() as u64;
    run_simulation(api_base, count, threads, elevators, max_floor, &sent, None, run_id)?;

    let secs = start.elapsed().as_secs_f64();
    let total = sent.load(Ordering::Relaxed);
    let rate = if secs > 0.0 { total as f64 / secs } else { 0.0 };
    println!("done: {total} orders in {secs:.2}s ({rate:.0} order/s)");
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn worker(
    api_base: &str,
    tid: u64,
    n: u64,
    elevators: &[String],
    max_floor: i32,
    sent: &AtomicU64,
    pace: Option<Duration>,
    run_id: u64,
) -> Result<(), BoxErr> {
    let agent = api::agent();
    let mut rng = rand::thread_rng();
    let delay = pace
        .filter(|_| n > 0)
        .map(|p| p / n as u32)
        .filter(|d| *d >= Duration::from_millis(1));
    for i in 0..n {
        let elevator = &elevators[rng.gen_range(0..elevators.len())];
        let floor = rng.gen_range(0..=max_floor);
        let tag = sim_tag(run_id, tid, i);
        if api::post_order_retry(&agent, api_base, elevator, floor, &tag).is_ok() {
            sent.fetch_add(1, Ordering::Relaxed);
        }
        if let Some(d) = delay {
            std::thread::sleep(d);
        }
    }
    Ok(())
}
