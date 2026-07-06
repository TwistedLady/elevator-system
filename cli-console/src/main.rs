mod api;
mod itest;
mod monitor;
mod rng;
mod sender;

use clap::{Parser, Subcommand};

pub type BoxErr = Box<dyn std::error::Error + Send + Sync>;

#[derive(Parser)]
#[command(
    name = "cli-console",
    about = "Monitor elevators and send orders — entirely through the elevator HTTP API (never Kafka)"
)]
struct Cli {
    /// Base URL of the elevator-api. Everything (orders, state, status, health) goes through it.
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
    /// Live multi-view TUI dashboard (chart, trend, orders, sim, health, logs, k8s, test).
    Monitor {
        #[arg(long, default_value = "logs/app.log")]
        app_log: String,
        #[arg(long, default_value = "logs/api.log")]
        api_log: String,
    },
    /// Send a single order.
    Order {
        #[arg(long)]
        elevator: String,
        #[arg(long)]
        floor: i32,
    },
    /// Fire a burst of random orders (load/sim).
    Simulate {
        #[arg(long, default_value_t = 10_000)]
        count: u64,
        #[arg(long, default_value_t = 4)]
        threads: u64,
        #[arg(
            long,
            value_delimiter = ',',
            default_value = "e1,e2,e3,e4,e5,e6,e7,e8,e9,e10"
        )]
        elevators: Vec<String>,
        #[arg(long)]
        elevator_count: Option<usize>,
        #[arg(long)]
        elevators_file: Option<String>,
        #[arg(long, default_value_t = 15)]
        max_floor: i32,
    },
    /// Headless one-shot check: API health + live state, writes a pass/fail log.
    Selftest {
        #[arg(long, default_value_t = 8)]
        duration: u64,
        #[arg(long, default_value = "logs/console-selftest.log")]
        log: String,
    },
    /// Automated integration test: fire orders, poll status, cross-check kubectl logs.
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
}

fn main() {
    let cli = Cli::parse();
    let result = match cli.command {
        Command::Monitor { app_log, api_log } => monitor::run(&cli.api, &app_log, &api_log),
        Command::Order { elevator, floor } => sender::send_one(&cli.api, &elevator, floor)
            .map(|()| println!("sent order: elevator={elevator} floor={floor}")),
        Command::Simulate {
            count,
            threads,
            elevators,
            elevator_count,
            elevators_file,
            max_floor,
        } => resolve_elevators(elevators, elevator_count, elevators_file)
            .and_then(|list| sender::simulate(&cli.api, count, threads, &list, max_floor)),
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
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

fn resolve_elevators(
    elevators: Vec<String>,
    count: Option<usize>,
    file: Option<String>,
) -> Result<Vec<String>, BoxErr> {
    let mut names = match (count, file) {
        (Some(n), _) if n > 0 => (1..=n).map(|i| format!("e{i}")).collect::<Vec<_>>(),
        (_, Some(path)) => read_elevators_file(&path)?,
        _ => elevators,
    };
    if names.is_empty() {
        return Err("no elevators specified".into());
    }
    names.sort_by(|a, b| natural_key(a).cmp(&natural_key(b)));
    names.dedup();
    Ok(names)
}

fn read_elevators_file(path: &str) -> Result<Vec<String>, BoxErr> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("cannot read elevators file '{path}': {e}"))?;
    let names = parse_elevators_text(&text);
    if names.is_empty() {
        return Err(format!("no elevator names found in '{path}'").into());
    }
    Ok(names)
}

/// Parse a fleet file: one or more comma-separated names per line, `#` starts a comment. Blank
/// entries are dropped. Kept pure (text in, names out) so it can be unit tested without the disk.
fn parse_elevators_text(text: &str) -> Vec<String> {
    text.lines()
        .map(|l| l.split('#').next().unwrap_or(""))
        .flat_map(|l| l.split(','))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(String::from)
        .collect()
}

fn natural_key(name: &str) -> (&str, u64) {
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

    #[test]
    fn explicit_list_is_sorted_and_deduped() {
        let out = resolve_elevators(v(&["e2", "e1", "e2", "e10"]), None, None).unwrap();
        assert_eq!(out, v(&["e1", "e2", "e10"]));
    }

    #[test]
    fn elevator_count_wins_over_the_list() {
        let out = resolve_elevators(v(&["ignored"]), Some(3), None).unwrap();
        assert_eq!(out, v(&["e1", "e2", "e3"]));
    }

    #[test]
    fn empty_selection_is_an_error() {
        assert!(resolve_elevators(vec![], None, None).is_err());
    }

    #[test]
    fn fleet_file_parsing_handles_comments_commas_and_blanks() {
        let text = "e1, e2 # first two\n\n  e3  \n# comment only\ne4,e5\n";
        assert_eq!(
            parse_elevators_text(text),
            v(&["e1", "e2", "e3", "e4", "e5"])
        );
    }
}
