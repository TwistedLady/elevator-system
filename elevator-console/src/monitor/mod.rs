mod app;
mod k8s;
mod selftest;
mod sources;
mod ui;
mod watch;

pub use selftest::run_selftest;
pub use watch::run_watch;

use std::io::IsTerminal;
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use ratatui::crossterm::event::{self, Event};

use crate::BoxErr;
use app::{App, ElevatorState, HealthSnapshot, LogSource};
use k8s::K8sSnapshot;

pub fn run(
    brokers: &str,
    state_topic: &str,
    command_topic: &str,
    health_url: &str,
    app_log: &str,
    api_log: &str,
) -> Result<(), BoxErr> {
    if !std::io::stdout().is_terminal() {
        return Err("`monitor` needs a real terminal (TTY); it can't run in a captured shell \
                    like the in-session `!` bash. Run it in your own terminal window, or use \
                    `watch` (headless live view) or `selftest` here."
            .into());
    }

    let api_base = health_url.strip_suffix("/actuator/health").unwrap_or(health_url);
    let mut app = App::new(brokers, command_topic, api_base);

    let (state_tx, state_rx) = mpsc::channel::<ElevatorState>();
    sources::spawn_consumer(brokers.to_string(), state_topic.to_string(), state_tx);

    let health = Arc::new(Mutex::new(HealthSnapshot::default()));
    sources::spawn_health_poll(health_url.to_string(), Arc::clone(&health));

    let (log_tx, log_rx) = mpsc::channel::<(LogSource, String)>();
    sources::spawn_log_tail(LogSource::App, app_log.to_string(), log_tx.clone());
    sources::spawn_log_tail(LogSource::Api, api_log.to_string(), log_tx);

    let k8s_state = Arc::new(Mutex::new(K8sSnapshot::default()));
    k8s::spawn_k8s_poll(Arc::clone(&k8s_state));

    let mut terminal = ratatui::init();
    let result = event_loop(&mut terminal, &mut app, &state_rx, &log_rx, &health, &k8s_state);
    ratatui::restore();
    result
}

fn event_loop(
    terminal: &mut ratatui::DefaultTerminal,
    app: &mut App,
    state_rx: &mpsc::Receiver<ElevatorState>,
    log_rx: &mpsc::Receiver<(LogSource, String)>,
    health: &Arc<Mutex<HealthSnapshot>>,
    k8s_state: &Arc<Mutex<K8sSnapshot>>,
) -> Result<(), BoxErr> {
    loop {
        while let Ok(state) = state_rx.try_recv() {
            app.record_state(state);
        }
        while let Ok((source, line)) = log_rx.try_recv() {
            app.push_log(source, line);
        }
        if let Ok(h) = health.lock() {
            app.health = h.clone();
        }
        if let Ok(k) = k8s_state.lock() {
            app.k8s = k.clone();
        }
        app.refresh_sim();

        terminal.draw(|frame| ui::draw(frame, app))?;

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
