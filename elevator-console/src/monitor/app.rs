use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use regex::{Regex, RegexBuilder};
use serde::Deserialize;

use super::k8s::K8sSnapshot;

pub const MAX_LOG_LINES: usize = 1000;
pub const SIM_MAX_FLOOR: i32 = 15;
pub const TREND_WINDOW_SECS: f64 = 60.0;

#[derive(Debug, Clone, Deserialize)]
pub struct ElevatorState {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    pub direction: String,
    pub motion: String,
    pub floor: i32,
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
    pub total: u64,
    pub elevators: usize,
    pub sent: Arc<AtomicU64>,
    pub done: Arc<AtomicBool>,
    pub start: Instant,
    pub elapsed: Option<Duration>,
    pub checked: u64,
    pub status_done: Arc<AtomicU64>,
    pub status_progress: Arc<AtomicU64>,
}

impl SimRun {
    pub fn sent(&self) -> u64 {
        self.sent.load(Ordering::Relaxed)
    }

    pub fn ratio(&self) -> f64 {
        if self.total == 0 {
            0.0
        } else {
            (self.sent() as f64 / self.total as f64).clamp(0.0, 1.0)
        }
    }

    pub fn done(&self) -> u64 {
        self.status_done.load(Ordering::Relaxed)
    }

    pub fn in_progress(&self) -> u64 {
        self.status_progress.load(Ordering::Relaxed)
    }

    pub fn pending(&self) -> u64 {
        self.checked
            .saturating_sub(self.done() + self.in_progress())
    }

    pub fn finished(&self) -> bool {
        self.elapsed.is_some()
    }

    pub fn secs(&self) -> f64 {
        self.elapsed
            .unwrap_or_else(|| self.start.elapsed())
            .as_secs_f64()
    }
}

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

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum View {
    Chart,
    Trend,
    Order,
    Sim,
    Health,
    Logs,
    K8s,
    Test,
}

impl View {
    pub const ALL: [View; 8] = [
        View::Chart,
        View::Trend,
        View::Order,
        View::Sim,
        View::Health,
        View::Logs,
        View::K8s,
        View::Test,
    ];

    pub fn index(self) -> usize {
        match self {
            View::Chart => 0,
            View::Trend => 1,
            View::Order => 2,
            View::Sim => 3,
            View::Health => 4,
            View::Logs => 5,
            View::K8s => 6,
            View::Test => 7,
        }
    }

    pub fn title(self) -> &'static str {
        match self {
            View::Chart => "① CHART",
            View::Trend => "② TREND",
            View::Order => "③ ORDER",
            View::Sim => "④ SIM",
            View::Health => "⑤ HEALTH",
            View::Logs => "⑥ LOGS",
            View::K8s => "⑦ K8S",
            View::Test => "⑧ TEST",
        }
    }

    pub fn next(self) -> View {
        View::ALL[(self.index() + 1) % View::ALL.len()]
    }

    pub fn prev(self) -> View {
        View::ALL[(self.index() + View::ALL.len() - 1) % View::ALL.len()]
    }
}

pub struct App {
    pub view: View,
    pub latest: BTreeMap<String, ElevatorState>,
    pub history: BTreeMap<String, VecDeque<(f64, f64)>>,
    pub seen_elevators: BTreeSet<String>,
    pub seen_floor_max: i32,
    pub k8s: K8sSnapshot,
    k8s_action: Arc<Mutex<Option<String>>>,
    pub test_report: Option<serde_json::Value>,
    pub test_running: Arc<std::sync::atomic::AtomicBool>,
    test_was_running: bool,
    start: Instant,
    start_unix: i64,
    pub health: HealthSnapshot,
    pub app_logs: VecDeque<String>,
    pub api_logs: VecDeque<String>,
    pub log_source: LogSource,
    pub log_filter: String,
    pub log_re: Option<Regex>,
    pub log_re_err: bool,
    pub elevator_filter: String,
    pub elevator_re: Option<Regex>,
    pub elevator_re_err: bool,
    pub sim_input: String,
    pub order_input: String,
    pub sim: Option<SimRun>,
    pub message: String,
    pub should_quit: bool,
    api_base: String,
    pub git: super::git::GitInfo,
}

impl App {
    pub fn new(api_base: &str) -> Self {
        let mut app = Self {
            view: View::Chart,
            latest: BTreeMap::new(),
            history: BTreeMap::new(),
            seen_elevators: BTreeSet::new(),
            seen_floor_max: 0,
            k8s: K8sSnapshot::default(),
            k8s_action: Arc::new(Mutex::new(None)),
            test_report: None,
            test_running: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            test_was_running: false,
            start: Instant::now(),
            start_unix: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0),
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
            api_base: api_base.to_string(),
            git: super::git::GitInfo::snapshot(),
        };
        app.reload_test_report();
        app
    }

    const TEST_REPORT_PATH: &'static str = "logs/itest-report.json";

    fn reload_test_report(&mut self) {
        self.test_report = std::fs::read_to_string(Self::TEST_REPORT_PATH)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok());
    }

    pub fn refresh_sim(&mut self) {
        if let Some(sim) = &mut self.sim {
            if sim.elapsed.is_none() && sim.done.load(Ordering::Relaxed) {
                sim.elapsed = Some(sim.start.elapsed());
            }
        }
        if let Some(msg) = self.k8s_action.lock().ok().and_then(|mut g| g.take()) {
            self.message = msg;
        }
        let running = self.test_running.load(Ordering::Relaxed);
        if self.test_was_running && !running {
            self.reload_test_report();
            let verdict = self
                .test_report
                .as_ref()
                .and_then(|r| r["verdict"].as_str())
                .unwrap_or("?");
            self.message = format!("integration test: {verdict}");
        }
        self.test_was_running = running;
    }

    pub fn record_state(&mut self, state: ElevatorState) {
        let t = self.start.elapsed().as_secs_f64();
        let hist = self.history.entry(state.elevator_name.clone()).or_default();
        hist.push_back((t, state.floor as f64));
        while matches!(hist.front(), Some(&(t0, _)) if t - t0 > TREND_WINDOW_SECS) {
            hist.pop_front();
        }
        self.seen_elevators.insert(state.elevator_name.clone());
        self.seen_floor_max = self.seen_floor_max.max(state.floor);
        self.latest.insert(state.elevator_name.clone(), state);
    }

    pub fn now_secs(&self) -> f64 {
        self.start.elapsed().as_secs_f64()
    }

    pub fn clock_at(&self, secs_since_start: f64) -> String {
        local_hms(self.start_unix + secs_since_start.round() as i64)
    }

    pub fn now_clock(&self) -> String {
        let unix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(self.start_unix);
        local_hms(unix)
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

    fn elevator_pool(&self) -> Vec<String> {
        if self.seen_elevators.is_empty() {
            (1..=10).map(|i| format!("e{i}")).collect()
        } else {
            self.seen_elevators.iter().cloned().collect()
        }
    }

    pub fn fleet(&self) -> Vec<String> {
        let mut v: Vec<String> = self.seen_elevators.iter().cloned().collect();
        v.sort_by(|a, b| crate::natural_key(a).cmp(&crate::natural_key(b)));
        v
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
            View::K8s => self.on_key_k8s(key),
            View::Test => self.on_key_test(key),
            View::Health => {}
        }
    }

    fn on_key_test(&mut self, key: KeyEvent) {
        if !matches!(key.code, KeyCode::Char('r') | KeyCode::Char('R')) {
            return;
        }
        if self.test_running.load(Ordering::Relaxed) {
            self.message = "test already running…".to_string();
            return;
        }
        self.message = "running integration test…".to_string();
        self.test_running.store(true, Ordering::Relaxed);
        self.test_was_running = true;
        let running = Arc::clone(&self.test_running);
        let api_base = self.api_base.clone();
        std::thread::spawn(move || {
            let _ = crate::itest::run_itest(&api_base, 12, 75, App::TEST_REPORT_PATH, true);
            running.store(false, Ordering::Relaxed);
        });
    }

    fn on_key_k8s(&mut self, key: KeyEvent) {
        let target = match key.code {
            KeyCode::Char('f') | KeyCode::Char('F') => "fast",
            KeyCode::Char('s') | KeyCode::Char('S') => "slow",
            _ => return,
        };
        if self.k8s.mode == target {
            self.message = format!("already {target}");
            return;
        }
        self.message = format!("switching to {target}…");
        let slot = Arc::clone(&self.k8s_action);
        std::thread::spawn(move || {
            let result =
                super::k8s::set_mode(target).unwrap_or_else(|e| format!("switch failed: {e}"));
            if let Ok(mut g) = slot.lock() {
                *g = Some(result);
            }
        });
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

    fn start_sim(&mut self, count: u64) {
        let pool = self.elevator_pool();
        let elevators = pool.len();
        let api_sim = self.api_base.clone();
        let sent = Arc::new(AtomicU64::new(0));
        let done = Arc::new(AtomicBool::new(false));
        let (sent_t, done_t) = (Arc::clone(&sent), Arc::clone(&done));
        let threads = count.clamp(1, 4);
        let run_id = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let pace = Some(Duration::from_millis(2500));
        std::thread::spawn(move || {
            let _ = crate::sender::run_simulation(
                &api_sim,
                count,
                threads,
                &pool,
                SIM_MAX_FLOOR,
                &sent_t,
                pace,
                run_id,
            );
            done_t.store(true, Ordering::Relaxed);
        });

        let tags = crate::sender::sim_tags(run_id, count, threads, count as usize);
        let checked = tags.len() as u64;
        let status_done = Arc::new(AtomicU64::new(0));
        let status_progress = Arc::new(AtomicU64::new(0));
        let (done_t2, prog_t2, api_base) = (
            Arc::clone(&status_done),
            Arc::clone(&status_progress),
            self.api_base.clone(),
        );
        std::thread::spawn(move || poll_statuses(&api_base, tags, &done_t2, &prog_t2));

        self.sim = Some(SimRun {
            total: count,
            elevators,
            sent,
            done,
            start: Instant::now(),
            elapsed: None,
            checked,
            status_done,
            status_progress,
        });
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

    fn on_key_logs(&mut self, key: KeyEvent) {
        match key.code {
            KeyCode::Left => self.log_source = LogSource::App,
            KeyCode::Right => self.log_source = LogSource::Api,
            KeyCode::Enter => {
                self.log_filter.clear();
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

    fn recompile_filter(&mut self) {
        if self.log_filter.is_empty() {
            self.log_re = None;
            self.log_re_err = false;
        } else {
            match RegexBuilder::new(&self.log_filter)
                .case_insensitive(true)
                .build()
            {
                Ok(re) => {
                    self.log_re = Some(re);
                    self.log_re_err = false;
                }
                Err(_) => {
                    self.log_re = None;
                    self.log_re_err = true;
                }
            }
        }
    }

    fn run_sim(&mut self, line: &str) -> String {
        let line = line.trim();
        if line.is_empty() {
            return String::new();
        }
        let lower = line.to_ascii_lowercase();
        let rest = if lower.starts_with("sim") {
            line[3..].trim()
        } else {
            line
        };
        match rest.parse::<u64>() {
            Ok(count) if count > 0 => {
                self.start_sim(count);
                format!("running {count} orders")
            }
            Ok(_) => "count must be greater than 0".to_string(),
            Err(_) => "type a number of orders, e.g. 300".to_string(),
        }
    }

    fn run_order(&mut self, line: &str) -> String {
        let parts: Vec<&str> = line.split_whitespace().collect();
        match parts.as_slice() {
            [] => String::new(),
            [elevator, floor] => match floor.parse::<i32>() {
                Ok(f) => {
                    let (api_base, name) = (self.api_base.clone(), elevator.to_string());
                    std::thread::spawn(move || {
                        let _ = crate::sender::send_one(&api_base, &name, f);
                    });
                    format!("ordered {elevator} → floor {f}")
                }
                Err(_) => "floor must be a number".to_string(),
            },
            _ => "type:  <elevator> <floor>   e.g.  e3 7".to_string(),
        }
    }
}

fn local_hms(unix: i64) -> String {
    let t = unix as libc::time_t;
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    unsafe { libc::localtime_r(&t, &mut tm) };
    format!("{:02}:{:02}:{:02}", tm.tm_hour, tm.tm_min, tm.tm_sec)
}

fn poll_statuses(api_base: &str, tags: Vec<String>, done: &AtomicU64, in_progress: &AtomicU64) {
    if tags.is_empty() {
        return;
    }
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(2))
        .build();
    let total = tags.len() as u64;
    let mut pending = tags;
    let start = Instant::now();
    while !pending.is_empty() && start.elapsed() < Duration::from_secs(600) {
        let mut progress = 0u64;
        pending.retain(|tag| {
            let url = format!("{api_base}/api/order/{tag}");
            match agent.get(&url).call() {
                Ok(resp) => {
                    let body = resp.into_string().unwrap_or_default();
                    if body.contains("\"status\":\"DONE\"") {
                        false
                    } else {
                        if body.contains("\"status\":\"PROGRESS\"") {
                            progress += 1;
                        }
                        true
                    }
                }
                Err(_) => true,
            }
        });
        done.store(total - pending.len() as u64, Ordering::Relaxed);
        in_progress.store(progress, Ordering::Relaxed);
        if pending.is_empty() {
            break;
        }
        std::thread::sleep(Duration::from_millis(500));
    }
}
