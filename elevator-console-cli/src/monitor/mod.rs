mod app;
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

use crate::api;
use crate::BoxErr;
use app::{App, DoorState, ElevatorState, HealthSnapshot};

pub fn run(api_base: &str) -> Result<(), BoxErr> {
    if !std::io::stdout().is_terminal() {
        return Err(
            "`monitor` needs a real terminal (TTY); it can't run in a captured shell \
                    like the in-session `!` bash. Run it in your own terminal window, or use \
                    `watch` (headless live view) or `selftest` here."
                .into(),
        );
    }

    let mut app = App::new(api_base);

    let (state_tx, state_rx) = mpsc::channel::<ElevatorState>();
    sources::spawn_state_source(api_base.to_string(), state_tx);

    let (door_tx, door_rx) = mpsc::channel::<DoorState>();
    sources::spawn_door_source(api_base.to_string(), door_tx);

    let health = Arc::new(Mutex::new(HealthSnapshot::default()));
    sources::spawn_health_poll(api::health_url(api_base), Arc::clone(&health));

    let backend_version = Arc::new(Mutex::new(None::<String>));
    sources::spawn_version_poll(api_base.to_string(), Arc::clone(&backend_version));

    let config = Arc::new(Mutex::new(crate::api::ElevatorConfig::default()));
    sources::spawn_config_poll(api_base.to_string(), Arc::clone(&config));

    let mut terminal = ratatui::init();
    let result = event_loop(
        &mut terminal,
        &mut app,
        &state_rx,
        &door_rx,
        &Shared {
            health: &health,
            backend_version: &backend_version,
            config: &config,
        },
    );
    ratatui::restore();
    result
}

/// The background-polled shared state the event loop copies into `App` each tick.
struct Shared<'a> {
    health: &'a Arc<Mutex<HealthSnapshot>>,
    backend_version: &'a Arc<Mutex<Option<String>>>,
    config: &'a Arc<Mutex<crate::api::ElevatorConfig>>,
}

fn event_loop(
    terminal: &mut ratatui::DefaultTerminal,
    app: &mut App,
    state_rx: &mpsc::Receiver<ElevatorState>,
    door_rx: &mpsc::Receiver<DoorState>,
    shared: &Shared,
) -> Result<(), BoxErr> {
    loop {
        while let Ok(state) = state_rx.try_recv() {
            app.record_state(state);
        }
        while let Ok(door) = door_rx.try_recv() {
            app.record_door(door);
        }
        if let Ok(h) = shared.health.lock() {
            app.health = h.clone();
        }
        if let Ok(v) = shared.backend_version.lock() {
            app.backend_version = v.clone();
        }
        if let Ok(c) = shared.config.lock() {
            app.config = c.clone();
        }

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
