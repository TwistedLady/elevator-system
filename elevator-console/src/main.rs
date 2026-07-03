mod itest;
mod monitor;
mod sender;

use clap::{Parser, Subcommand};

pub type BoxErr = Box<dyn std::error::Error + Send + Sync>;

#[derive(Parser)]
#[command(
    name = "elevator-console",
    about = "Monitor elevators and send orders over Kafka"
)]
struct Cli {
    #[arg(
        long,
        env = "KAFKA_BOOTSTRAP",
        default_value = "localhost:9094",
        global = true
    )]
    brokers: String,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    Monitor {
        #[arg(long, env = "STATE_TOPIC", default_value = "elevator-state")]
        topic: String,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        command_topic: String,
        #[arg(
            long,
            env = "HEALTH_URL",
            default_value = "http://localhost:8080/actuator/health"
        )]
        health_url: String,
        #[arg(long, default_value = "logs/app.log")]
        app_log: String,
        #[arg(long, default_value = "logs/api.log")]
        api_log: String,
    },
    Order {
        #[arg(long)]
        elevator: String,
        #[arg(long)]
        floor: i32,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        topic: String,
    },
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
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        topic: String,
    },
    Selftest {
        #[arg(long, env = "STATE_TOPIC", default_value = "elevator-state")]
        topic: String,
        #[arg(
            long,
            env = "HEALTH_URL",
            default_value = "http://localhost:8080/actuator/health"
        )]
        health_url: String,
        #[arg(long, default_value_t = 8)]
        duration: u64,
        #[arg(long, default_value = "logs/console-selftest.log")]
        log: String,
    },
    Itest {
        #[arg(long, default_value_t = 20)]
        count: u64,
        #[arg(long, env = "COMMAND_TOPIC", default_value = "elevator-commands")]
        topic: String,
        #[arg(
            long,
            env = "HEALTH_URL",
            default_value = "http://localhost:8080/actuator/health"
        )]
        health_url: String,
        #[arg(long, default_value_t = 90)]
        timeout: u64,
        #[arg(long, default_value = "logs/itest-report.json")]
        out: String,
    },
    Watch {
        #[arg(long, env = "STATE_TOPIC", default_value = "elevator-state")]
        topic: String,
        #[arg(long, default_value_t = 1000)]
        refresh_ms: u64,
        #[arg(long)]
        duration: Option<u64>,
    },
}

fn main() {
    let cli = Cli::parse();
    let result = match cli.command {
        Command::Monitor {
            topic,
            command_topic,
            health_url,
            app_log,
            api_log,
        } => monitor::run(
            &cli.brokers,
            &topic,
            &command_topic,
            &health_url,
            &app_log,
            &api_log,
        ),
        Command::Order {
            elevator,
            floor,
            topic,
        } => sender::send_one(&cli.brokers, &topic, &elevator, floor)
            .map(|()| println!("sent order: elevator={elevator} floor={floor}")),
        Command::Simulate {
            count,
            threads,
            elevators,
            elevator_count,
            elevators_file,
            max_floor,
            topic,
        } => resolve_elevators(elevators, elevator_count, elevators_file).and_then(|list| {
            sender::simulate(&cli.brokers, &topic, count, threads, &list, max_floor)
        }),
        Command::Selftest {
            topic,
            health_url,
            duration,
            log,
        } => monitor::run_selftest(&cli.brokers, &topic, &health_url, duration, &log),
        Command::Watch {
            topic,
            refresh_ms,
            duration,
        } => monitor::run_watch(&cli.brokers, &topic, refresh_ms, duration),
        Command::Itest {
            count,
            topic,
            health_url,
            timeout,
            out,
        } => itest::run_itest(
            &cli.brokers,
            &topic,
            &health_url,
            count,
            timeout,
            &out,
            false,
        ),
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
    let names: Vec<String> = text
        .lines()
        .map(|l| l.split('#').next().unwrap_or(""))
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

fn natural_key(name: &str) -> (&str, u64) {
    match name.find(|c: char| c.is_ascii_digit()) {
        Some(i) => {
            let (prefix, num) = name.split_at(i);
            (prefix, num.parse::<u64>().unwrap_or(u64::MAX))
        }
        None => (name, u64::MAX),
    }
}
