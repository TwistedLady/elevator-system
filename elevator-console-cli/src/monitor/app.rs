//! Monitor TUI state: the `App` model plus `SimRun`, whose background thread POSTs `/api/simulate`
//! then polls progress into shared atomics that the UI only reads.
use std::collections::{BTreeMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use regex::{Regex, RegexBuilder};
use serde::Deserialize;

pub const TREND_WINDOW_SECS: f64 = 60.0;

#[derive(Debug, Clone, Deserialize)]
pub struct ElevatorState {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    pub direction: String,
    pub motion: String,
    pub floor: i32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DoorState {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    #[serde(rename = "doorState")]
    pub door_state: String,
}

#[derive(Clone)]
pub struct HealthComp {
    pub name: String,
    pub status: String,
    pub detail: String,
}

#[derive(Clone)]
pub struct HealthSnapshot {
    pub reachable: bool,
    pub overall: String,
    pub components: Vec<HealthComp>,
}

impl Default for HealthSnapshot {
    fn default() -> Self {
        Self {
            reachable: false,
            overall: "?".to_string(),
            components: Vec::new(),
        }
    }
}

pub struct SimRun {
    pub run_id: Arc<Mutex<String>>,
    pub size: Arc<AtomicU64>,
    pub calls: Arc<AtomicU64>,
    pub orders: Arc<AtomicU64>,
    pub done_calls: Arc<AtomicU64>,
    pub first_call: Arc<Mutex<Option<String>>>,
    pub last_done: Arc<Mutex<Option<String>>>,
    pub complete: Arc<AtomicBool>,
    pub error: Arc<Mutex<Option<String>>>,
}

impl SimRun {
    fn new() -> Self {
        Self {
            run_id: Arc::new(Mutex::new(String::new())),
            size: Arc::new(AtomicU64::new(0)),
            calls: Arc::new(AtomicU64::new(0)),
            orders: Arc::new(AtomicU64::new(0)),
            done_calls: Arc::new(AtomicU64::new(0)),
            first_call: Arc::new(Mutex::new(None)),
            last_done: Arc::new(Mutex::new(None)),
            complete: Arc::new(AtomicBool::new(false)),
            error: Arc::new(Mutex::new(None)),
        }
    }

    pub fn run_id(&self) -> String {
        self.run_id.lock().map(|g| g.clone()).unwrap_or_default()
    }

    pub fn size(&self) -> u64 {
        self.size.load(Ordering::Relaxed)
    }

    pub fn calls(&self) -> u64 {
        self.calls.load(Ordering::Relaxed)
    }

    pub fn orders(&self) -> u64 {
        self.orders.load(Ordering::Relaxed)
    }

    pub fn done(&self) -> u64 {
        self.done_calls.load(Ordering::Relaxed)
    }

    pub fn in_progress(&self) -> u64 {
        self.calls().saturating_sub(self.done())
    }

    pub fn pending(&self) -> u64 {
        self.size().saturating_sub(self.calls())
    }

    pub fn first_call(&self) -> Option<String> {
        self.first_call.lock().ok().and_then(|g| g.clone())
    }

    pub fn last_done(&self) -> Option<String> {
        self.last_done.lock().ok().and_then(|g| g.clone())
    }

    pub fn complete(&self) -> bool {
        self.complete.load(Ordering::Relaxed)
    }

    pub fn error(&self) -> Option<String> {
        self.error.lock().ok().and_then(|g| g.clone())
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum View {
    Chart,
    Trend,
    Sim,
}

impl View {
    pub const ALL: [View; 3] = [View::Chart, View::Trend, View::Sim];

    pub fn title(self) -> &'static str {
        match self {
            View::Chart => "CHART",
            View::Trend => "TREND",
            View::Sim => "SIM",
        }
    }
}

pub struct App {
    pub view: View,
    pub latest: BTreeMap<String, ElevatorState>,
    pub doors: BTreeMap<String, DoorState>,
    pub history: BTreeMap<String, VecDeque<(f64, f64)>>,
    start: Instant,
    start_unix: i64,
    pub health: HealthSnapshot,
    pub backend_version: Option<String>,
    pub elevator_filter: String,
    pub elevator_re: Option<Regex>,
    pub elevator_re_err: bool,
    pub sim: Option<SimRun>,
    pub message: String,
    pub should_quit: bool,
    api_base: String,
    pub config: crate::api::ElevatorConfig,
}

impl App {
    pub fn new(api_base: &str) -> Self {
        Self {
            view: View::Chart,
            latest: BTreeMap::new(),
            doors: BTreeMap::new(),
            history: BTreeMap::new(),
            start: Instant::now(),
            start_unix: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0),
            health: HealthSnapshot::default(),
            backend_version: None,
            elevator_filter: String::new(),
            elevator_re: None,
            elevator_re_err: false,
            sim: None,
            message: String::new(),
            should_quit: false,
            api_base: api_base.to_string(),
            config: crate::api::ElevatorConfig::default(),
        }
    }

    pub fn record_state(&mut self, state: ElevatorState) {
        let t = self.start.elapsed().as_secs_f64();
        let hist = self.history.entry(state.elevator_name.clone()).or_default();
        hist.push_back((t, state.floor as f64));
        while matches!(hist.front(), Some(&(t0, _)) if t - t0 > TREND_WINDOW_SECS) {
            hist.pop_front();
        }
        self.latest.insert(state.elevator_name.clone(), state);
    }

    pub fn record_door(&mut self, door: DoorState) {
        self.doors.insert(door.elevator_name.clone(), door);
    }

    pub fn now_secs(&self) -> f64 {
        self.start.elapsed().as_secs_f64()
    }

    pub fn clock_at(&self, secs_since_start: f64) -> String {
        local_hms(self.start_unix + secs_since_start.round() as i64)
    }

    fn cycle_view(&mut self, delta: isize) {
        let views = View::ALL;
        let cur = views.iter().position(|v| *v == self.view).unwrap_or(0);
        let n = views.len() as isize;
        let idx = (cur as isize + delta).rem_euclid(n) as usize;
        self.view = views[idx];
    }

    pub fn on_key(&mut self, key: KeyEvent) {
        if key.kind != KeyEventKind::Press && key.kind != KeyEventKind::Repeat {
            return;
        }
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
                self.cycle_view(1);
                return;
            }
            KeyCode::BackTab => {
                self.cycle_view(-1);
                return;
            }
            _ => {}
        }
        match self.view {
            View::Chart | View::Trend => self.on_key_elevator_filter(key),
            View::Sim => self.on_key_sim(key),
        }
    }

    fn on_key_sim(&mut self, key: KeyEvent) {
        if matches!(key.code, KeyCode::Char('r') | KeyCode::Char('R')) {
            self.start_sim();
        }
    }

    fn start_sim(&mut self) {
        let run = SimRun::new();
        let api_base = self.api_base.clone();
        let run_id = Arc::clone(&run.run_id);
        let size = Arc::clone(&run.size);
        let calls = Arc::clone(&run.calls);
        let orders = Arc::clone(&run.orders);
        let done_calls = Arc::clone(&run.done_calls);
        let first_call = Arc::clone(&run.first_call);
        let last_done = Arc::clone(&run.last_done);
        let complete = Arc::clone(&run.complete);
        let error = Arc::clone(&run.error);
        std::thread::spawn(move || {
            let agent = crate::api::agent();
            match crate::api::post_simulate(&agent, &api_base) {
                Ok(resp) => {
                    if let Ok(mut g) = run_id.lock() {
                        *g = resp.run_id.clone();
                    }
                    size.store(resp.count, Ordering::Relaxed);
                    poll_progress(
                        &agent,
                        &api_base,
                        &resp.run_id,
                        resp.count,
                        &calls,
                        &orders,
                        &done_calls,
                        &first_call,
                        &last_done,
                        &complete,
                    );
                }
                Err(e) => {
                    if let Ok(mut g) = error.lock() {
                        *g = Some(format!("simulate failed: {e}"));
                    }
                }
            }
        });
        self.sim = Some(run);
        self.message = "starting a simulation…".to_string();
    }

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
            match RegexBuilder::new(&self.elevator_filter)
                .case_insensitive(true)
                .build()
            {
                Ok(re) => {
                    self.elevator_re = Some(re);
                    self.elevator_re_err = false;
                }
                Err(_) => {
                    self.elevator_re = None;
                    self.elevator_re_err = true;
                }
            }
        }
    }

    pub fn elevator_matches(&self, name: &str) -> bool {
        match &self.elevator_re {
            Some(re) => re.is_match(name),
            None => true,
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn poll_progress(
    agent: &ureq::Agent,
    api_base: &str,
    run_id: &str,
    size: u64,
    calls: &AtomicU64,
    orders: &AtomicU64,
    done_calls: &AtomicU64,
    first_call: &Mutex<Option<String>>,
    last_done: &Mutex<Option<String>>,
    complete: &AtomicBool,
) {
    let deadline = Instant::now() + Duration::from_secs(1800);
    while Instant::now() < deadline {
        if let Ok(p) = crate::api::get_progress(agent, api_base, run_id, size) {
            calls.store(p.calls, Ordering::Relaxed);
            orders.store(p.orders, Ordering::Relaxed);
            done_calls.store(p.done_calls, Ordering::Relaxed);
            if let Ok(mut g) = first_call.lock() {
                *g = p.first_call;
            }
            if let Ok(mut g) = last_done.lock() {
                *g = p.last_done;
            }
            if size > 0 && p.done_calls >= size {
                complete.store(true, Ordering::Relaxed);
                return;
            }
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn local_hms(unix: i64) -> String {
    let t = unix as libc::time_t;
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    unsafe { libc::localtime_r(&t, &mut tm) };
    format!("{:02}:{:02}:{:02}", tm.tm_hour, tm.tm_min, tm.tm_sec)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn view_cycles_through_the_three_tabs() {
        let mut app = App::new("http://localhost:8080");
        assert_eq!(app.view, View::Chart);
        app.cycle_view(1);
        assert_eq!(app.view, View::Trend);
        app.cycle_view(1);
        assert_eq!(app.view, View::Sim);
        app.cycle_view(1);
        assert_eq!(app.view, View::Chart);
        app.cycle_view(-1);
        assert_eq!(app.view, View::Sim);
    }

    #[test]
    fn tab_titles_are_the_three_uppercase_names() {
        let titles: Vec<&str> = View::ALL.iter().map(|v| v.title()).collect();
        assert_eq!(titles, vec!["CHART", "TREND", "SIM"]);
    }

    #[test]
    fn elevator_filter_matches_by_regex_and_falls_back_to_all_when_empty() {
        let mut app = App::new("http://localhost:8080");
        assert!(app.elevator_matches("e1"));
        app.elevator_filter = "e1".to_string();
        app.recompile_elevator_filter();
        assert!(app.elevator_matches("e1"));
        assert!(!app.elevator_matches("e2"));
    }

    #[test]
    fn elevator_state_deserializes_from_api_json() {
        let json = r#"{"elevatorName":"e2","direction":"UP","motion":"MOVING","floor":4}"#;
        let s: ElevatorState = serde_json::from_str(json).unwrap();
        assert_eq!(s.elevator_name, "e2");
        assert_eq!(s.floor, 4);
        assert_eq!(s.direction, "UP");
    }
}
