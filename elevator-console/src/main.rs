//! Terminal app for the elevator system.
//!
//! Three subcommands:
//!   monitor   — retro TUI: building chart, actuator health, live logs (Tab to switch)
//!   order     — send one order (an elevator to a floor)
//!   simulate  — fire a bulk load of random orders (load simulator)

mod monitor;
mod sender;

use clap::{Parser, Subcommand};

/// Error type shared across the app. `Send + Sync` so it can cross thread boundaries
/// (the simulator returns it from worker threads).
pub type BoxErr = Box<dyn std::error::Error + Send + Sync>;

#[derive(Parser)]
#[command(name = "elevator-console", about = "Monitor elevators and send orders over Kafka")]
struct Cli {
    /// Kafka bootstrap servers.
    #[arg(long, env = "KAFKA_BOOTSTRAP", default_value = "localhost:9092", global = true)]
    brokers: String,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Retro TUI: building chart, actuator health, and live logs (Tab to switch).
    Monitor {
        #[arg(long, env = "STATE_TOPIC", default_value = "elevator-state")]
        topic: String,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        command_topic: String,
        /// elevator-api actuator URL polled for backend health (full /actuator/health).
        #[arg(long, env = "HEALTH_URL", default_value = "http://localhost:8080/actuator/health")]
        health_url: String,
        /// elevator-app log file tailed in the Logs view.
        #[arg(long, default_value = "../logs/app.log")]
        app_log: String,
        /// elevator-api log file tailed in the Logs view.
        #[arg(long, default_value = "../logs/api.log")]
        api_log: String,
    },
    /// Send one order: an elevator to a floor.
    Order {
        /// Elevator name (used as the Kafka message key).
        #[arg(long)]
        elevator: String,
        /// Target floor.
        #[arg(long)]
        floor: i32,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        topic: String,
    },
    /// Fire a bulk load of random orders (load simulator).
    Simulate {
        /// Total number of orders to send.
        #[arg(long, default_value_t = 10_000)]
        count: u64,
        /// Producer threads, each with its own connection.
        #[arg(long, default_value_t = 4)]
        threads: u64,
        /// Elevators to spread orders across (comma-separated).
        #[arg(long, value_delimiter = ',', default_value = "alpha,beta,gamma")]
        elevators: Vec<String>,
        /// Number of elevators to generate, named e1..eN. Overrides --elevators.
        #[arg(long)]
        elevator_count: Option<usize>,
        /// Read elevator names from a file (one per line or comma-separated; '#' = comment).
        #[arg(long)]
        elevators_file: Option<String>,
        /// Highest floor to request; orders pick a floor in 0..=max-floor.
        #[arg(long, default_value_t = 10)]
        max_floor: i32,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        topic: String,
    },
}

fn main() {
    let cli = Cli::parse();
    let result = match cli.command {
        Command::Monitor { topic, command_topic, health_url, app_log, api_log } => {
            monitor::run(&cli.brokers, &topic, &command_topic, &health_url, &app_log, &api_log)
        }
        Command::Order { elevator, floor, topic } => {
            sender::send_one(&cli.brokers, &topic, &elevator, floor)
                .map(|()| println!("sent order: elevator={elevator} floor={floor}"))
        }
        Command::Simulate { count, threads, elevators, elevator_count, elevators_file, max_floor, topic } => {
            resolve_elevators(elevators, elevator_count, elevators_file)
                .and_then(|list| sender::simulate(&cli.brokers, &topic, count, threads, &list, max_floor))
        }
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

/// Decide the elevator list for `simulate`. Precedence:
///   --elevator-count N  >  --elevators-file PATH  >  --elevators LIST
/// The result is de-duplicated and natural-sorted (e1, e2, … e10) so the names are
/// always ordered, whatever the source.
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

/// Read elevator names from a file: one per line or comma-separated, '#' starts a
/// comment, blank entries ignored.
fn read_elevators_file(path: &str) -> Result<Vec<String>, BoxErr> {
    let text = std::fs::read_to_string(path)
        .map_err(|e| format!("cannot read elevators file '{path}': {e}"))?;
    let names: Vec<String> = text
        .lines()
        .map(|l| l.split('#').next().unwrap_or("")) // strip trailing comment
        .flat_map(|l| l.split(','))
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(String::from)
        .collect();
    if names.is_empty() {
        return Err(format!("no elevator names found in '{path}'").into());
    }
    Ok(names)
}

/// Natural-order key: split a name into its non-digit prefix and trailing number, so
/// "e2" sorts before "e10" instead of lexicographically after it.
fn natural_key(name: &str) -> (&str, u64) {
    match name.find(|c: char| c.is_ascii_digit()) {
        Some(i) => {
            let (prefix, num) = name.split_at(i);
            (prefix, num.parse::<u64>().unwrap_or(u64::MAX))
        }
        None => (name, u64::MAX),
    }
}
