//! Application state and the logic that mutates it (no rendering, no I/O threads here).

use std::collections::{BTreeMap, VecDeque};
use std::time::Instant;

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use regex::{Regex, RegexBuilder};
use serde::Deserialize;

/// Max log lines kept per source (older ones are dropped).
pub const MAX_LOG_LINES: usize = 1000;
/// Floors used by the `sim` command when generating random orders.
pub const SIM_MAX_FLOOR: i32 = 15;
/// How many seconds of floor history the TREND chart keeps/shows.
pub const TREND_WINDOW_SECS: f64 = 60.0;

/// Mirrors `ElevatorStateDto` published by elevator-app.
#[derive(Debug, Clone, Deserialize)]
pub struct ElevatorState {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    pub direction: String,
    pub motion: String,
    pub floor: i32,
}

/// One component from the actuator /actuator/health response.
#[derive(Clone)]
pub struct HealthComp {
    pub name: String,
    pub status: String,
    pub detail: String,
}

/// Snapshot of the api's actuator health.
#[derive(Clone)]
pub struct HealthSnapshot {
    pub reachable: bool,
    pub overall: String,
    pub components: Vec<HealthComp>,
}

impl Default for HealthSnapshot {
    fn default() -> Self {
        Self { reachable: false, overall: "?".to_string(), components: Vec::new() }
    }
}

/// Which backend log the Logs view is showing.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum LogSource {
    App,
    Api,
}

impl LogSource {
    pub fn label(self) -> &'static str {
        match self {
            LogSource::App => "elevator-app",
            LogSource::Api => "elevator-api",
        }
    }
}

/// The three tabs.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum View {
    Chart,
    Trend,
    Health,
    Logs,
}

impl View {
    pub const ALL: [View; 4] = [View::Chart, View::Trend, View::Health, View::Logs];

    pub fn index(self) -> usize {
        match self {
            View::Chart => 0,
            View::Trend => 1,
            View::Health => 2,
            View::Logs => 3,
        }
    }

    /// Funny old-school tab labels with circled-number icons.
    pub fn title(self) -> &'static str {
        match self {
            View::Chart => "① CHART",
            View::Trend => "② TREND",
            View::Health => "③ HEALTH",
            View::Logs => "④ LOGS",
        }
    }

    pub fn next(self) -> View {
        View::ALL[(self.index() + 1) % View::ALL.len()]
    }

    pub fn prev(self) -> View {
        View::ALL[(self.index() + View::ALL.len() - 1) % View::ALL.len()]
    }
}

/// All UI state. Background threads feed it through channels (see `sources`).
pub struct App {
    pub view: View,
    pub latest: BTreeMap<String, ElevatorState>,
    /// Per-elevator floor history as (seconds-since-start, floor), for the TREND chart.
    pub history: BTreeMap<String, VecDeque<(f64, f64)>>,
    start: Instant,
    pub health: HealthSnapshot,
    pub app_logs: VecDeque<String>,
    pub api_logs: VecDeque<String>,
    pub log_source: LogSource,
    /// Raw regex text the user is typing in the Logs view.
    pub log_filter: String,
    /// Compiled filter (None = show all, or pattern is empty/invalid).
    pub log_re: Option<Regex>,
    /// True when `log_filter` is non-empty but not a valid regex.
    pub log_re_err: bool,
    pub input: String,
    pub message: String,
    pub should_quit: bool,
    brokers: String,
    command_topic: String,
}

impl App {
    pub fn new(brokers: &str, command_topic: &str) -> Self {
        Self {
            view: View::Chart,
            latest: BTreeMap::new(),
            history: BTreeMap::new(),
            start: Instant::now(),
            health: HealthSnapshot::default(),
            app_logs: VecDeque::new(),
            api_logs: VecDeque::new(),
            log_source: LogSource::App,
            log_filter: String::new(),
            log_re: None,
            log_re_err: false,
            input: String::new(),
            message: String::new(),
            should_quit: false,
            brokers: brokers.to_string(),
            command_topic: command_topic.to_string(),
        }
    }

    pub fn record_state(&mut self, state: ElevatorState) {
        // Append to the floor-over-time history and drop points older than the window.
        let t = self.start.elapsed().as_secs_f64();
        let hist = self.history.entry(state.elevator_name.clone()).or_default();
        hist.push_back((t, state.floor as f64));
        while matches!(hist.front(), Some(&(t0, _)) if t - t0 > TREND_WINDOW_SECS) {
            hist.pop_front();
        }
        self.latest.insert(state.elevator_name.clone(), state);
    }

    /// Seconds since the monitor started (X axis "now" for the trend chart).
    pub fn now_secs(&self) -> f64 {
        self.start.elapsed().as_secs_f64()
    }

    pub fn push_log(&mut self, source: LogSource, line: String) {
        let buf = match source {
            LogSource::App => &mut self.app_logs,
            LogSource::Api => &mut self.api_logs,
        };
        if buf.len() >= MAX_LOG_LINES {
            buf.pop_front();
        }
        buf.push_back(line);
    }

    pub fn logs(&self) -> &VecDeque<String> {
        match self.log_source {
            LogSource::App => &self.app_logs,
            LogSource::Api => &self.api_logs,
        }
    }

    /// Elevators for `sim`: those seen so far, or a default 8 if none yet.
    fn elevator_pool(&self) -> Vec<String> {
        if self.latest.is_empty() {
            (1..=8).map(|i| format!("lift-{i:02}")).collect()
        } else {
            self.latest.keys().cloned().collect()
        }
    }

    /// Single entry point for keyboard input; dispatches per active view.
    pub fn on_key(&mut self, key: KeyEvent) {
        if key.kind != KeyEventKind::Press && key.kind != KeyEventKind::Repeat {
            return;
        }
        // Global keys first.
        match key.code {
            KeyCode::Char('c') if key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.should_quit = true;
                return;
            }
            KeyCode::Esc => {
                self.should_quit = true;
                return;
            }
            KeyCode::Tab => {
                self.view = self.view.next();
                return;
            }
            KeyCode::BackTab => {
                self.view = self.view.prev();
                return;
            }
            _ => {}
        }
        match self.view {
            View::Chart => self.on_key_chart(key),
            View::Logs => self.on_key_logs(key),
            View::Health | View::Trend => {}
        }
    }

    fn on_key_chart(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Enter => {
                let line = std::mem::take(&mut self.input);
                self.message = self.run_command(&line);
            }
            KeyCode::Backspace => {
                self.input.pop();
            }
            KeyCode::Char(c) => self.input.push(c),
            _ => {}
        }
    }

    fn on_key_logs(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Left => self.log_source = LogSource::App,
            KeyCode::Right => self.log_source = LogSource::Api,
            KeyCode::Enter => {
                self.log_filter.clear(); // Enter resets the filter
                self.recompile_filter();
            }
            KeyCode::Backspace => {
                self.log_filter.pop();
                self.recompile_filter();
            }
            KeyCode::Char(c) => {
                self.log_filter.push(c);
                self.recompile_filter();
            }
            _ => {}
        }
    }

    /// Recompile the regex (case-insensitive) whenever the filter text changes.
    fn recompile_filter(&mut self) {
        if self.log_filter.is_empty() {
            self.log_re = None;
            self.log_re_err = false;
        } else {
            match RegexBuilder::new(&self.log_filter).case_insensitive(true).build() {
                Ok(re) => {
                    self.log_re = Some(re);
                    self.log_re_err = false;
                }
                Err(_) => {
                    self.log_re = None; // keep showing all lines while the pattern is half-typed
                    self.log_re_err = true;
                }
            }
        }
    }

    /// Parse an input line into an order or a `sim`, producing off-thread so the UI
    /// never blocks. Returns a short status message for the footer.
    fn run_command(&self, line: &str) -> String {
        let parts: Vec<&str> = line.split_whitespace().collect();
        match parts.as_slice() {
            [] => String::new(),
            [cmd, n] if cmd.eq_ignore_ascii_case("sim") => match n.parse::<u64>() {
                Ok(count) => {
                    let (brokers, topic) = (self.brokers.clone(), self.command_topic.clone());
                    let pool = self.elevator_pool();
                    std::thread::spawn(move || {
                        let _ = crate::sender::simulate(
                            &brokers,
                            &topic,
                            count,
                            count.clamp(1, 4),
                            &pool,
                            SIM_MAX_FLOOR,
                        );
                    });
                    format!("sent sim {count}")
                }
                Err(_) => "usage: sim <count>".to_string(),
            },
            [elevator, floor] => match floor.parse::<i32>() {
                Ok(f) => {
                    let (brokers, topic, name) =
                        (self.brokers.clone(), self.command_topic.clone(), elevator.to_string());
                    std::thread::spawn(move || {
                        let _ = crate::sender::send_one(&brokers, &topic, &name, f);
                    });
                    format!("ordered {elevator} -> floor {f}")
                }
                Err(_) => "floor must be a number".to_string(),
            },
            _ => "type:  <elevator> <floor>   or   sim <count>".to_string(),
        }
    }
}
