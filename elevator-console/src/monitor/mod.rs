//! `monitor` subcommand: a retro multi-view TUI (Chart / Health / Logs).
//!
//! Structure:
//!   app      — all UI state + key handling (no I/O)
//!   sources  — background threads feeding state in (Kafka / health / logs)
//!   ui       — rendering only
//! This module just wires them together and runs the draw/event loop.

mod app;
mod sources;
mod ui;

use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ratatui::crossterm::event::{self, Event};

use crate::BoxErr;
use app::{App, ElevatorState, HealthSnapshot, LogSource};

pub fn run(
    brokers: &str,
    state_topic: &str,
    command_topic: &str,
    health_url: &str,
    app_log: &str,
    api_log: &str,
) -> Result<(), BoxErr> {
    // The api base is the health URL minus the actuator path (e.g. http://localhost:8080).
    let api_base = health_url.strip_suffix("/actuator/health").unwrap_or(health_url);
    let mut app = App::new(brokers, command_topic, api_base);

    // Kafka state -> channel.
    let (state_tx, state_rx) = mpsc::channel::<ElevatorState>();
    sources::spawn_consumer(brokers.to_string(), state_topic.to_string(), state_tx);

    // Actuator health -> shared snapshot.
    let health = Arc::new(Mutex::new(HealthSnapshot::default()));
    sources::spawn_health_poll(health_url.to_string(), Arc::clone(&health));

    // Backend logs -> channel (both files share one channel, tagged by source).
    let (log_tx, log_rx) = mpsc::channel::<(LogSource, String)>();
    sources::spawn_log_tail(LogSource::App, app_log.to_string(), log_tx.clone());
    sources::spawn_log_tail(LogSource::Api, api_log.to_string(), log_tx);

    // ratatui::init() enables raw mode + alternate screen and installs a panic hook
    // that restores the terminal; ratatui::restore() undoes it on the way out.
    let mut terminal = ratatui::init();
    let result = event_loop(&mut terminal, &mut app, &state_rx, &log_rx, &health);
    ratatui::restore();
    result
}

fn event_loop(
    terminal: &mut ratatui::DefaultTerminal,
    app: &mut App,
    state_rx: &mpsc::Receiver<ElevatorState>,
    log_rx: &mpsc::Receiver<(LogSource, String)>,
    health: &Arc<Mutex<HealthSnapshot>>,
) -> Result<(), BoxErr> {
    loop {
        // Drain whatever the background threads produced.
        while let Ok(state) = state_rx.try_recv() {
            app.record_state(state);
        }
        while let Ok((source, line)) = log_rx.try_recv() {
            app.push_log(source, line);
        }
        if let Ok(h) = health.lock() {
            app.health = h.clone();
        }
        app.refresh_sim();

        terminal.draw(|frame| ui::draw(frame, app))?;

        // Block up to 150ms for a key, so we stay responsive without busy-looping.
        if event::poll(Duration::from_millis(150))? {
            if let Event::Key(key) = event::read()? {
                app.on_key(key);
            }
        }
        if app.should_quit {
            break;
        }
    }
    Ok(())
}
