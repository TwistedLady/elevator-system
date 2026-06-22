//! Application state and the logic that mutates it (no rendering, no I/O threads here).

use std::collections::{BTreeMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

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

/// State of an in-flight (or finished) simulation — drives the SIM tab's progress bar.
/// A worker thread bumps `sent`; the UI thread only reads it.
pub struct SimRun {
    pub total: u64,
    pub elevators: usize,
    pub sent: Arc<AtomicU64>,
    pub done: Arc<AtomicBool>,
    pub start: Instant,
    /// Set once when the run finishes, so the elapsed time stops ticking.
    pub elapsed: Option<Duration>,
    /// How many of the sent orders we verify against the API (capped — see CHECK_CAP).
    pub checked: u64,
    /// Orders confirmed processed (status DONE) by the API; bumped by the poller thread.
    pub processed: Arc<AtomicU64>,
}

impl SimRun {
    pub fn sent(&self) -> u64 {
        self.sent.load(Ordering::Relaxed)
    }

    /// Fraction sent, clamped to 0.0..=1.0 (safe to hand straight to a Gauge).
    pub fn ratio(&self) -> f64 {
        if self.total == 0 {
            0.0
        } else {
            (self.sent() as f64 / self.total as f64).clamp(0.0, 1.0)
        }
    }

    pub fn processed(&self) -> u64 {
        self.processed.load(Ordering::Relaxed)
    }

    /// Fraction of the *checked* orders confirmed processed by the API.
    pub fn processed_ratio(&self) -> f64 {
        if self.checked == 0 {
            0.0
        } else {
            (self.processed() as f64 / self.checked as f64).clamp(0.0, 1.0)
        }
    }

    pub fn finished(&self) -> bool {
        self.elapsed.is_some()
    }

    /// Seconds elapsed: frozen once finished, otherwise live.
    pub fn secs(&self) -> f64 {
        self.elapsed.unwrap_or_else(|| self.start.elapsed()).as_secs_f64()
    }
}

/// Most orders we verify against the API after a sim (one HTTP GET per tag, polled until done).
const CHECK_CAP: usize = 100;

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
    Order,
    Sim,
    Health,
    Logs,
}

impl View {
    pub const ALL: [View; 6] =
        [View::Chart, View::Trend, View::Order, View::Sim, View::Health, View::Logs];

    pub fn index(self) -> usize {
        match self {
            View::Chart => 0,
            View::Trend => 1,
            View::Order => 2,
            View::Sim => 3,
            View::Health => 4,
            View::Logs => 5,
        }
    }

    /// Funny old-school tab labels with circled-number icons.
    pub fn title(self) -> &'static str {
        match self {
            View::Chart => "① CHART",
            View::Trend => "② TREND",
            View::Order => "③ ORDER",
            View::Sim => "④ SIM",
            View::Health => "⑤ HEALTH",
            View::Logs => "⑥ LOGS",
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
    /// Elevator-name filter for the Chart and Trend views (regex, case-insensitive).
    pub elevator_filter: String,
    pub elevator_re: Option<Regex>,
    pub elevator_re_err: bool,
    /// Count typed in the SIM view (how many random orders to fire).
    pub sim_input: String,
    /// "<elevator> <floor>" typed in the ORDER view.
    pub order_input: String,
    /// Current/last simulation run, for the SIM tab's progress bar.
    pub sim: Option<SimRun>,
    /// Short feedback message shown in the ORDER/SIM footers (last action result).
    pub message: String,
    pub should_quit: bool,
    brokers: String,
    command_topic: String,
    /// Base URL of the elevator-api (e.g. http://localhost:8080), for the order-status check.
    api_base: String,
}

impl App {
    pub fn new(brokers: &str, command_topic: &str, api_base: &str) -> Self {
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
            elevator_filter: String::new(),
            elevator_re: None,
            elevator_re_err: false,
            sim_input: String::new(),
            order_input: String::new(),
            sim: None,
            message: String::new(),
            should_quit: false,
            brokers: brokers.to_string(),
            command_topic: command_topic.to_string(),
            api_base: api_base.to_string(),
        }
    }

    /// Per-frame upkeep: freeze a finished run's elapsed time so it stops ticking.
    pub fn refresh_sim(&mut self) {
        if let Some(sim) = &mut self.sim {
            if sim.elapsed.is_none() && sim.done.load(Ordering::Relaxed) {
                sim.elapsed = Some(sim.start.elapsed());
            }
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

    /// Elevators for `sim`: those seen so far, or a default e1..e8 if none yet (matches the
    /// demo's naming, so an early sim targets the real fleet instead of spawning phantoms).
    fn elevator_pool(&self) -> Vec<String> {
        if self.latest.is_empty() {
            (1..=8).map(|i| format!("e{i}")).collect()
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
            View::Chart | View::Trend => self.on_key_elevator_filter(key),
            View::Order => self.on_key_order(key),
            View::Sim => self.on_key_sim(key),
            View::Logs => self.on_key_logs(key),
            View::Health => {}
        }
    }

    fn on_key_order(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Enter => {
                let line = std::mem::take(&mut self.order_input);
                self.message = self.run_order(line.trim());
            }
            KeyCode::Backspace => {
                self.order_input.pop();
            }
            KeyCode::Char(c) => self.order_input.push(c),
            _ => {}
        }
    }

    fn on_key_sim(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Enter => {
                let line = std::mem::take(&mut self.sim_input);
                self.message = self.run_sim(line.trim());
            }
            KeyCode::Backspace => {
                self.sim_input.pop();
            }
            KeyCode::Char(c) => self.sim_input.push(c),
            _ => {}
        }
    }

    /// Kick off a simulation on a background thread, tracking progress via a shared counter.
    fn start_sim(&mut self, count: u64) {
        let pool = self.elevator_pool();
        let elevators = pool.len();
        let (brokers, topic) = (self.brokers.clone(), self.command_topic.clone());
        let sent = Arc::new(AtomicU64::new(0));
        let done = Arc::new(AtomicBool::new(false));
        let (sent_t, done_t) = (Arc::clone(&sent), Arc::clone(&done));
        let threads = count.clamp(1, 4);
        // Unique per run so a re-run uses fresh tags (not deduped) and shows fresh progress.
        let run_id = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        // Spread the run over ~2.5s so the progress bar visibly fills; big runs that take
        // longer than that to send simply ignore the pacing and go full speed.
        let pace = Some(Duration::from_millis(2500));
        std::thread::spawn(move || {
            let _ = crate::sender::run_simulation(
                &brokers, &topic, count, threads, &pool, SIM_MAX_FLOOR, &sent_t, pace, run_id,
            );
            done_t.store(true, Ordering::Relaxed);
        });

        // Verify (up to CHECK_CAP of) the sent orders against the API's order-status endpoint.
        let tags = crate::sender::sim_tags(run_id, count, threads, CHECK_CAP);
        let checked = tags.len() as u64;
        let processed = Arc::new(AtomicU64::new(0));
        let (processed_t, api_base) = (Arc::clone(&processed), self.api_base.clone());
        std::thread::spawn(move || poll_processed(&api_base, tags, &processed_t));

        self.sim = Some(SimRun {
            total: count,
            elevators,
            sent,
            done,
            start: Instant::now(),
            elapsed: None,
            checked,
            processed,
        });
    }

    /// Chart/Trend filter: type to narrow which elevators are shown; Enter clears it.
    fn on_key_elevator_filter(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Enter => {
                self.elevator_filter.clear();
                self.recompile_elevator_filter();
            }
            KeyCode::Backspace => {
                self.elevator_filter.pop();
                self.recompile_elevator_filter();
            }
            KeyCode::Char(c) => {
                self.elevator_filter.push(c);
                self.recompile_elevator_filter();
            }
            _ => {}
        }
    }

    fn recompile_elevator_filter(&mut self) {
        if self.elevator_filter.is_empty() {
            self.elevator_re = None;
            self.elevator_re_err = false;
        } else {
            match RegexBuilder::new(&self.elevator_filter).case_insensitive(true).build() {
                Ok(re) => {
                    self.elevator_re = Some(re);
                    self.elevator_re_err = false;
                }
                Err(_) => {
                    self.elevator_re = None; // keep showing all while the pattern is half-typed
                    self.elevator_re_err = true;
                }
            }
        }
    }

    /// Whether an elevator passes the Chart/Trend name filter (all pass if no/invalid filter).
    pub fn elevator_matches(&self, name: &str) -> bool {
        match &self.elevator_re {
            Some(re) => re.is_match(name),
            None => true,
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

    /// SIM input: a count (bare number, or "sim 300"). Runs that many random orders off-thread,
    /// tracked by the progress bar. Returns a footer status message.
    fn run_sim(&mut self, line: &str) -> String {
        let line = line.trim();
        if line.is_empty() {
            return String::new();
        }
        // Tolerate a leading "sim" keyword ("sim 300", "sim300") — it's natural to type.
        let lower = line.to_ascii_lowercase();
        let rest = if lower.starts_with("sim") { line[3..].trim() } else { line };
        match rest.parse::<u64>() {
            Ok(count) if count > 0 => {
                self.start_sim(count);
                format!("running {count} orders")
            }
            Ok(_) => "count must be greater than 0".to_string(),
            Err(_) => "type a number of orders, e.g. 300".to_string(),
        }
    }

    /// ORDER input: "<elevator> <floor>" sends one order off-thread (so the UI never blocks).
    fn run_order(&mut self, line: &str) -> String {
        let parts: Vec<&str> = line.split_whitespace().collect();
        match parts.as_slice() {
            [] => String::new(),
            [elevator, floor] => match floor.parse::<i32>() {
                Ok(f) => {
                    let (brokers, topic, name) =
                        (self.brokers.clone(), self.command_topic.clone(), elevator.to_string());
                    std::thread::spawn(move || {
                        let _ = crate::sender::send_one(&brokers, &topic, &name, f);
                    });
                    format!("ordered {elevator} → floor {f}")
                }
                Err(_) => "floor must be a number".to_string(),
            },
            _ => "type:  <elevator> <floor>   e.g.  e3 7".to_string(),
        }
    }
}

/// Poll the API's `GET /api/order/{tag}` for each tag until it reports processed (or we give up),
/// bumping `processed` as each is confirmed. Runs on its own thread; the SIM tab reads the count.
fn poll_processed(api_base: &str, tags: Vec<String>, processed: &AtomicU64) {
    if tags.is_empty() {
        return;
    }
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(2))
        .build();
    let mut pending = tags;
    let start = Instant::now();
    while !pending.is_empty() && start.elapsed() < Duration::from_secs(120) {
        pending.retain(|tag| {
            let url = format!("{api_base}/api/order/{tag}");
            match agent.get(&url).call() {
                // 200 with processed:true -> confirmed, drop it. Any other state -> keep polling.
                Ok(resp) => {
                    let done = resp.into_string().unwrap_or_default().contains("\"processed\":true");
                    if done {
                        processed.fetch_add(1, Ordering::Relaxed);
                    }
                    !done
                }
                Err(_) => true, // 404 (not projected yet) or transient -> retry
            }
        });
        if pending.is_empty() {
            break;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}
