//! Call submission via the HTTP API (previously a Kafka producer). Single calls and the bulk
//! simulator both POST to `/api/call`; the API publishes them to the system.
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use crate::api;
use crate::rng::Rng;
use crate::BoxErr;

pub fn send_one(api_base: &str, elevator: &str, floor: i32) -> Result<(), BoxErr> {
    let agent = api::agent();
    let id = format!("cli-{}", std::process::id());
    api::post_call(&agent, api_base, elevator, floor, &id, None)
}

/// How many recurring riders the simulator draws from. A small pool means the same rider presses
/// many times, so the Manager's distinct-passenger count stays below the raw call count. The names
/// and password must match `passengers.properties` in elevator-api.
pub const PASSENGER_POOL: usize = 8;
pub const PASSENGER_PASSWORD: &str = "liftpass";

/// Decide the rider for a simulated call from a raw draw in `0..2 * PASSENGER_POOL`. The low half
/// is anonymous (`None`, no credentials); the high half maps to one of `PASSENGER_POOL` recurring
/// riders (`rider-N`), so roughly half the calls authenticate and identified riders repeat.
pub fn passenger_for(draw: usize) -> Option<String> {
    if draw < PASSENGER_POOL {
        None
    } else {
        Some(format!("rider-{}", draw - PASSENGER_POOL))
    }
}

pub fn call_id(run_id: u64, tid: u64, i: u64) -> String {
    format!("sim-{run_id}-{tid}-{i}")
}

pub fn call_ids(run_id: u64, count: u64, threads: u64, limit: usize) -> Vec<String> {
    let threads = threads.max(1);
    let per = count / threads;
    let rem = count % threads;
    let mut ids = Vec::new();
    for tid in 0..threads {
        let n = per + if tid < rem { 1 } else { 0 };
        for i in 0..n {
            if ids.len() >= limit {
                return ids;
            }
            ids.push(call_id(run_id, tid, i));
        }
    }
    ids
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
            handles.push(
                s.spawn(move || worker(api_base, t, n, elevators, max_floor, sent, pace, run_id)),
            );
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
        "simulating {count} calls across {} elevator(s) on {} thread(s) → {api_base}…",
        elevators.len(),
        threads.max(1)
    );
    let start = Instant::now();
    let run_id = std::process::id() as u64;
    run_simulation(
        api_base, count, threads, elevators, max_floor, &sent, None, run_id,
    )?;

    let secs = start.elapsed().as_secs_f64();
    let total = sent.load(Ordering::Relaxed);
    let rate = if secs > 0.0 { total as f64 / secs } else { 0.0 };
    println!("done: {total} calls in {secs:.2}s ({rate:.0} call/s)");
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
    let mut rng = Rng::seeded(run_id ^ (tid.wrapping_add(1).wrapping_mul(0x9E3779B9)));
    let delay = pace
        .filter(|_| n > 0)
        .map(|p| p / n as u32)
        .filter(|d| *d >= Duration::from_millis(1));
    for i in 0..n {
        let elevator = &elevators[rng.below(elevators.len())];
        let floor = rng.floor(max_floor);
        let id = call_id(run_id, tid, i);
        let rider = passenger_for(rng.below(PASSENGER_POOL * 2));
        let credentials = rider.as_deref().map(|u| (u, PASSENGER_PASSWORD));
        if api::post_call_retry(&agent, api_base, elevator, floor, &id, credentials).is_ok() {
            sent.fetch_add(1, Ordering::Relaxed);
        }
        if let Some(d) = delay {
            std::thread::sleep(d);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn id_format_is_stable() {
        assert_eq!(call_id(7, 2, 5), "sim-7-2-5");
    }

    #[test]
    fn ids_cover_the_full_count_and_are_unique() {
        let ids = call_ids(1, 100, 4, usize::MAX);
        assert_eq!(ids.len(), 100);
        assert_eq!(
            ids.iter().collect::<HashSet<_>>().len(),
            100,
            "no duplicates"
        );
    }

    #[test]
    fn ids_split_evenly_with_remainder_on_low_threads() {
        // 10 calls over 3 threads → 4,3,3. Thread 0 carries the remainder.
        let t0 = call_ids(1, 10, 3, usize::MAX)
            .into_iter()
            .filter(|t| t.starts_with("sim-1-0-"))
            .count();
        assert_eq!(t0, 4);
    }

    #[test]
    fn limit_caps_the_number_of_ids() {
        assert_eq!(call_ids(1, 100, 4, 12).len(), 12);
    }

    #[test]
    fn zero_threads_is_treated_as_one() {
        assert_eq!(call_ids(1, 5, 0, usize::MAX).len(), 5);
    }

    #[test]
    fn passenger_low_half_is_anonymous_high_half_is_a_rider() {
        assert_eq!(passenger_for(0), None);
        assert_eq!(passenger_for(PASSENGER_POOL - 1), None);
        assert_eq!(passenger_for(PASSENGER_POOL), Some("rider-0".to_string()));
        assert_eq!(
            passenger_for(2 * PASSENGER_POOL - 1),
            Some(format!("rider-{}", PASSENGER_POOL - 1))
        );
    }

    #[test]
    fn passenger_pool_size_bounds_distinct_riders() {
        let riders: HashSet<String> = (PASSENGER_POOL..2 * PASSENGER_POOL)
            .filter_map(passenger_for)
            .collect();
        assert_eq!(riders.len(), PASSENGER_POOL);
    }
}
