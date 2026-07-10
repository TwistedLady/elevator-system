mod api;
mod itest;
mod monitor;
mod version;

use clap::{Parser, Subcommand};

pub type BoxErr = Box<dyn std::error::Error + Send + Sync>;

#[derive(Parser)]
#[command(
    name = "elevator-console-cli",
    version = version::CONSOLE_VERSION,
    about = "Monitor elevators and send calls — entirely through the elevator HTTP API (never Kafka)"
)]
struct Cli {
    /// Base URL of the elevator-api. Everything (calls, state, status, health) goes through it.
    #[arg(
        long,
        env = "ELEVATOR_API",
        default_value = "https://localhost:8080",
        global = true
    )]
    api: String,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Live TUI dashboard: Chart, Trend, and a Sim tab that runs a server-side 10k simulation.
    Monitor,
    /// Send a single call.
    Call {
        #[arg(long)]
        elevator: String,
        #[arg(long)]
        floor: i32,
    },
    /// Trigger a server-side simulation (POST /api/simulate) and print the run id.
    Simulate {
        #[arg(long, default_value_t = 10_000)]
        count: u64,
    },
    /// Headless one-shot check: API health + live state, writes a pass/fail log.
    Selftest {
        #[arg(long, default_value_t = 8)]
        duration: u64,
        #[arg(long, default_value = "logs/console-selftest.log")]
        log: String,
    },
    /// Automated integration test: fire calls, poll status, cross-check kubectl logs.
    Itest {
        #[arg(long, default_value_t = 20)]
        count: u64,
        #[arg(long, default_value_t = 90)]
        timeout: u64,
        #[arg(long, default_value = "logs/itest-report.json")]
        out: String,
    },
    /// Headless plain-text live view of the latest state per elevator.
    Watch {
        #[arg(long, default_value_t = 1000)]
        refresh_ms: u64,
        #[arg(long)]
        duration: Option<u64>,
    },
    /// Show the console version and check it matches the backend API version.
    Version,
}

fn main() {
    let cli = Cli::parse();
    let result = match cli.command {
        Command::Monitor => monitor::run(&cli.api),
        Command::Call { elevator, floor } => run_call(&cli.api, &elevator, floor),
        Command::Simulate { count } => run_simulate(&cli.api, count),
        Command::Selftest { duration, log } => monitor::run_selftest(&cli.api, duration, &log),
        Command::Watch {
            refresh_ms,
            duration,
        } => monitor::run_watch(&cli.api, refresh_ms, duration),
        Command::Itest {
            count,
            timeout,
            out,
        } => itest::run_itest(&cli.api, count, timeout, &out, false),
        Command::Version => version::run(&cli.api),
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

/// Send a single call, pre-checking it against the API's live limits (the API stays authoritative).
fn run_call(api: &str, elevator: &str, floor: i32) -> Result<(), BoxErr> {
    let agent = api::agent();
    if let Ok(cfg) = api::get_config(&agent, api) {
        cfg.validate_call(elevator, floor)?;
    }
    let id = format!("cli-{}", std::process::id());
    api::post_call(&agent, api, elevator, floor, &id, None)?;
    println!("sent call: elevator={elevator} floor={floor}");
    Ok(())
}

/// Trigger a server-side simulation and print the run id. The api fires the calls; we don't.
fn run_simulate(api: &str, count: u64) -> Result<(), BoxErr> {
    let resp = api::post_simulate(&api::agent(), api, count)?;
    println!(
        "simulation started: run {} — {} calls fired by the api",
        resp.run_id, resp.count
    );
    Ok(())
}

pub(crate) fn natural_key(name: &str) -> (&str, u64) {
    match name.find(|c: char| c.is_ascii_digit()) {
        Some(i) => {
            let (prefix, num) = name.split_at(i);
            (prefix, num.parse::<u64>().unwrap_or(u64::MAX))
        }
        None => (name, u64::MAX),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(names: &[&str]) -> Vec<String> {
        names.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn natural_key_orders_numerically_not_lexically() {
        let mut names = v(&["e10", "e2", "e1"]);
        names.sort_by(|a, b| natural_key(a).cmp(&natural_key(b)));
        assert_eq!(names, v(&["e1", "e2", "e10"]));
    }
}
