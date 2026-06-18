//! Terminal app for the elevator system.
//!
//! Three subcommands:
//!   monitor   — live building chart; order inline by typing "<elevator> <floor>"
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
    /// Live building chart; type "<elevator> <floor>" + Enter to order inline.
    Monitor {
        #[arg(long, env = "STATE_TOPIC", default_value = "elevator-state")]
        topic: String,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        command_topic: String,
        /// elevator-api actuator URL polled for backend health.
        #[arg(long, env = "HEALTH_URL", default_value = "http://localhost:8080/actuator/health/readiness")]
        health_url: String,
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
        Command::Monitor { topic, command_topic, health_url } => {
            monitor::run(&cli.brokers, &topic, &command_topic, &health_url)
        }
        Command::Order { elevator, floor, topic } => {
            sender::send_one(&cli.brokers, &topic, &elevator, floor)
        }
        Command::Simulate { count, threads, elevators, max_floor, topic } => {
            sender::simulate(&cli.brokers, &topic, count, threads, &elevators, max_floor)
        }
    };
    if let Err(e) = result {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}
