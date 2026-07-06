use std::collections::BTreeSet;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::Path;
use std::process::Command;
use std::sync::atomic::AtomicU64;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use regex::Regex;

use crate::sender::{run_simulation, sim_tags};
use crate::BoxErr;

const FLEET: [&str; 10] = ["e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10"];
const MAX_FLOOR: i32 = 15;
const THREADS: u64 = 4;

pub fn run_itest(
    api_base: &str,
    count: u64,
    timeout_secs: u64,
    out_path: &str,
    quiet: bool,
) -> Result<(), BoxErr> {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(3))
        .build();
    let health_url = crate::api::health_url(api_base);

    let health_ok = matches!(
        agent.get(&health_url).call().map(|r| r.into_string().unwrap_or_default()),
        Ok(body) if body.contains("\"status\":\"UP\"")
    );
    if !quiet {
        eprintln!(
            "[itest] health {} ({health_url})",
            if health_ok { "UP" } else { "DOWN" }
        );
    }

    let run_id = now_ms();
    let fleet: Vec<String> = FLEET.iter().map(|s| s.to_string()).collect();
    let sent = AtomicU64::new(0);
    let sent_ms = now_ms();
    let send_start = Instant::now();
    run_simulation(
        api_base, count, THREADS, &fleet, MAX_FLOOR, &sent, None, run_id,
    )?;
    let send_secs = send_start.elapsed().as_secs_f64();
    if !quiet {
        eprintln!("[itest] sent {count} orders in {send_secs:.2}s (run {run_id})");
    }

    let mut pending: BTreeSet<String> = sim_tags(run_id, count, THREADS, count as usize)
        .into_iter()
        .collect();
    let mut latencies: Vec<u64> = Vec::new();
    let done = poll_done(
        &agent,
        api_base,
        &mut pending,
        &mut latencies,
        sent_ms,
        timeout_secs,
    );
    let lost = pending.len() as u64;
    latencies.sort_unstable();
    let stats = LatencyStats::from(&latencies);
    let total_secs = (now_ms() - sent_ms) as f64 / 1000.0;
    let throughput = if total_secs > 0.0 {
        done as f64 / total_secs
    } else {
        0.0
    };

    let api_log = kubectl_logs("elevator-api");
    let app_log = kubectl_logs("elevator-app");
    let done_re = Regex::new(&format!(r"\[order status\] (sim-{run_id}-\S+) -> DONE")).unwrap();
    let api_done: BTreeSet<&str> = done_re
        .captures_iter(&api_log)
        .map(|c| c.get(1).unwrap().as_str())
        .collect();
    let api_errors = api_log.matches("ERROR").count();
    let app_moves = app_log.matches(">>>").count();
    let stream_failed = app_log.contains("Kafka stream failed");
    let mode = current_mode();

    let checks: Vec<(String, bool)> = vec![
        ("console: lost == 0".into(), lost == 0),
        ("console: health UP".into(), health_ok),
        (
            format!("api log: >= {count} tags confirmed DONE"),
            api_done.len() as u64 >= count,
        ),
        ("app log: elevators moved".into(), app_moves > 0),
        ("app log: no 'Kafka stream failed'".into(), !stream_failed),
    ];
    let passed = checks.iter().all(|(_, ok)| *ok);

    let report = serde_json::json!({
        "run_id": run_id,
        "mode": mode,
        "requests": count,
        "done": done,
        "lost": lost,
        "send_secs": send_secs,
        "total_secs": total_secs,
        "throughput_done_per_s": throughput,
        "latency_ms": {
            "min": stats.min, "p50": stats.p50, "p95": stats.p95, "max": stats.max, "avg": stats.avg,
        },
        "api_confirmed_done": api_done.len(),
        "api_errors": api_errors,
        "app_moves_observed": app_moves,
        "app_stream_failed": stream_failed,
        "lost_tags": pending.iter().take(20).collect::<Vec<_>>(),
        "checks": checks.iter().map(|(n, ok)| serde_json::json!({"check": n, "ok": ok}))
            .collect::<Vec<_>>(),
        "verdict": if passed { "PASS" } else { "FAIL" },
    });
    write_report(out_path, &report)?;
    write_markdown(out_path, &report, &checks);

    if !quiet {
        print_summary(&report, &checks);
    }

    if !passed {
        let failed: Vec<&str> = checks
            .iter()
            .filter(|(_, ok)| !ok)
            .map(|(n, _)| n.as_str())
            .collect();
        return Err(format!("integration test FAILED: {}", failed.join("; ")).into());
    }
    Ok(())
}

fn poll_done(
    agent: &ureq::Agent,
    api_base: &str,
    pending: &mut BTreeSet<String>,
    latencies: &mut Vec<u64>,
    sent_ms: u64,
    timeout_secs: u64,
) -> u64 {
    let mut done = 0u64;
    let deadline = Instant::now() + Duration::from_secs(timeout_secs);
    while !pending.is_empty() && Instant::now() < deadline {
        let mut finished: Vec<String> = Vec::new();
        for tag in pending.iter() {
            let url = format!("{api_base}/api/order/{tag}");
            if let Ok(resp) = agent.get(&url).call() {
                if resp
                    .into_string()
                    .unwrap_or_default()
                    .contains("\"status\":\"DONE\"")
                {
                    latencies.push(now_ms().saturating_sub(sent_ms));
                    finished.push(tag.clone());
                }
            }
        }
        for tag in finished {
            pending.remove(&tag);
            done += 1;
        }
        if pending.is_empty() {
            break;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    done
}

fn kubectl_logs(dep: &str) -> String {
    let out = Command::new("kubectl")
        .args(["logs", &format!("deployment/{dep}"), "--tail=12000"])
        .output();
    match out {
        Ok(o) => strip_ansi(&String::from_utf8_lossy(&o.stdout)),
        Err(_) => String::new(),
    }
}

fn strip_ansi(s: &str) -> String {
    Regex::new(r"\x1b\[[0-9;]*m")
        .unwrap()
        .replace_all(s, "")
        .into_owned()
}

fn current_mode() -> String {
    let out = Command::new("kubectl")
        .args([
            "get",
            "deploy",
            "elevator-app",
            "-o",
            "jsonpath={.spec.template.spec.containers[0].envFrom[0].configMapRef.name}",
        ])
        .output();
    match out {
        Ok(o) => {
            let s = String::from_utf8_lossy(&o.stdout);
            let m = s.trim().replace("elevator-app-config-", "");
            if m.is_empty() {
                "?".into()
            } else {
                m
            }
        }
        Err(_) => "?".into(),
    }
}

fn print_summary(report: &serde_json::Value, checks: &[(String, bool)]) {
    let lm = &report["latency_ms"];
    println!("\n================ INTEGRATION TEST REPORT ================");
    println!(
        " mode={}  requests={}  done={}  lost={}",
        report["mode"], report["requests"], report["done"], report["lost"]
    );
    println!(
        " latency(ms): p50={}  p95={}  max={}  throughput={:.2}/s",
        lm["p50"],
        lm["p95"],
        lm["max"],
        report["throughput_done_per_s"].as_f64().unwrap_or(0.0)
    );
    println!(
        " api confirmed DONE: {}   api errors: {}   app moves: {}   stream-failed: {}",
        report["api_confirmed_done"],
        report["api_errors"],
        report["app_moves_observed"],
        report["app_stream_failed"]
    );
    for (name, ok) in checks {
        println!("   [{}] {name}", if *ok { "PASS" } else { "FAIL" });
    }
    println!(" VERDICT: {}", report["verdict"].as_str().unwrap_or("?"));
    println!("========================================================");
}

struct LatencyStats {
    min: u64,
    p50: u64,
    p95: u64,
    max: u64,
    avg: u64,
}

impl LatencyStats {
    fn from(sorted: &[u64]) -> Self {
        if sorted.is_empty() {
            return Self {
                min: 0,
                p50: 0,
                p95: 0,
                max: 0,
                avg: 0,
            };
        }
        let pct = |p: f64| sorted[((sorted.len() as f64 * p) as usize).min(sorted.len() - 1)];
        let sum: u64 = sorted.iter().sum();
        Self {
            min: sorted[0],
            p50: pct(0.50),
            p95: pct(0.95),
            max: sorted[sorted.len() - 1],
            avg: sum / sorted.len() as u64,
        }
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn write_report(path: &str, report: &serde_json::Value) -> Result<(), BoxErr> {
    if let Some(parent) = Path::new(path).parent() {
        std::fs::create_dir_all(parent).ok();
    }
    let mut f = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(path)?;
    writeln!(f, "{}", serde_json::to_string_pretty(report)?)?;
    Ok(())
}

fn write_markdown(json_path: &str, r: &serde_json::Value, checks: &[(String, bool)]) {
    let md_path = json_path
        .strip_suffix(".json")
        .unwrap_or(json_path)
        .to_string()
        + ".md";
    let lm = &r["latency_ms"];
    let rows: String = checks
        .iter()
        .map(|(n, ok)| format!("| {n} | {} |\n", if *ok { "✅" } else { "❌" }))
        .collect();
    let md = format!(
        "# Integration test report\n\n\
         **Verdict: {verdict}** (run `{run}`, mode `{mode}`)\n\n\
         | metric | value |\n|---|---|\n\
         | requests | {req} |\n| done | {done} |\n| **lost** | **{lost}** |\n\
         | latency p50 / p95 / max (ms) | {p50} / {p95} / {max} |\n\
         | throughput | {tput:.2}/s |\n\
         | api confirmed DONE | {apidone} |\n| api errors | {apierr} |\n\
         | app moves observed | {moves} |\n| app stream-failed | {sf} |\n\n\
         ## Checks\n| check | result |\n|---|---|\n{rows}",
        verdict = r["verdict"].as_str().unwrap_or("?"),
        run = r["run_id"],
        mode = r["mode"].as_str().unwrap_or("?"),
        req = r["requests"],
        done = r["done"],
        lost = r["lost"],
        p50 = lm["p50"],
        p95 = lm["p95"],
        max = lm["max"],
        tput = r["throughput_done_per_s"].as_f64().unwrap_or(0.0),
        apidone = r["api_confirmed_done"],
        apierr = r["api_errors"],
        moves = r["app_moves_observed"],
        sf = r["app_stream_failed"],
    );
    let _ = std::fs::write(md_path, md);
}
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn latency_stats_from_sorted_samples() {
        let sorted = vec![10u64, 20, 30, 40, 100];
        let s = LatencyStats::from(&sorted);
        assert_eq!(s.min, 10);
        assert_eq!(s.max, 100);
        assert_eq!(s.avg, 40); // (10+20+30+40+100)/5
        assert_eq!(s.p50, 30);
        assert_eq!(s.p95, 100);
    }

    #[test]
    fn latency_stats_empty_is_all_zero() {
        let s = LatencyStats::from(&[]);
        assert_eq!((s.min, s.p50, s.p95, s.max, s.avg), (0, 0, 0, 0, 0));
    }

    #[test]
    fn strip_ansi_removes_colour_codes() {
        let coloured = "\x1b[31mERROR\x1b[0m done";
        assert_eq!(strip_ansi(coloured), "ERROR done");
    }
}
