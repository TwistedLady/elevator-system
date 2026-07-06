use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use ratatui::crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use regex::{Regex, RegexBuilder};
use serde::Deserialize;

use super::k8s::K8sSnapshot;

pub const MAX_LOG_LINES: usize = 1000;
pub const TREND_WINDOW_SECS: f64 = 60.0;

#[derive(Debug, Clone, Deserialize)]
pub struct ElevatorState {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    pub direction: String,
    pub motion: String,
    pub floor: i32,
}

/// One row of `GET /api/mileage` (Spark streaming BI): floors travelled per elevator.
#[derive(Debug, Clone, Deserialize)]
pub struct MileageRow {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    #[serde(rename = "floorsTravelled")]
    pub floors_travelled: i64,
}

/// One row of `GET /api/served` (Spark batch BI): how many ordered floors the elevator reached.
#[derive(Debug, Clone, Deserialize)]
pub struct ServedRow {
    #[serde(rename = "elevatorName")]
    pub elevator_name: String,
    #[serde(rename = "ordersServed")]
    pub orders_served: i64,
}

/// A merged BI row for one elevator: mileage (floors travelled) + orders served.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StatsRow {
    pub name: String,
    pub mileage: i64,
    pub served: i64,
}

/// Merge the mileage and served lists into one per-elevator row set, keyed by elevator name
/// (an elevator present in either list appears once), sorted by mileage descending then by
/// natural name order. Pure: no I/O, easy to unit-test.
pub fn merge_stats(mileage: Vec<MileageRow>, served: Vec<ServedRow>) -> Vec<StatsRow> {
    let mut map: BTreeMap<String, StatsRow> = BTreeMap::new();
    for m in mileage {
        map.entry(m.elevator_name.clone())
            .or_insert(StatsRow {
                name: m.elevator_name,
                mileage: 0,
                served: 0,
            })
            .mileage = m.floors_travelled;
    }
    for s in served {
        map.entry(s.elevator_name.clone())
            .or_insert(StatsRow {
                name: s.elevator_name,
                mileage: 0,
                served: 0,
            })
            .served = s.orders_served;
    }
    let mut rows: Vec<StatsRow> = map.into_values().collect();
    rows.sort_by(|a, b| {
        b.mileage
            .cmp(&a.mileage)
            .then_with(|| crate::natural_key(&a.name).cmp(&crate::natural_key(&b.name)))
    });
    rows
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

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum View {
    Chart,
    Trend,
    Order,
    Sim,
    Health,
    Logs,
    K8s,
    Test,
    Stats,
}

impl View {
    pub const ALL: [View; 9] = [
        View::Chart,
        View::Trend,
        View::Order,
        View::Sim,
        View::Health,
        View::Logs,
        View::K8s,
        View::Test,
        View::Stats,
    ];

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
            View::Stats => "⑨ STATS",
        }
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
    pub stats: Vec<StatsRow>,
    pub message: String,
    pub should_quit: bool,
    api_base: String,
    pub config: crate::api::ElevatorConfig,
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
            stats: Vec::new(),
            message: String::new(),
            should_quit: false,
            api_base: api_base.to_string(),
            config: crate::api::ElevatorConfig::default(),
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
        if !self.config.bi_enabled && self.view == View::Stats {
            self.view = View::Chart;
        }
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
        if !self.seen_elevators.is_empty() {
            self.seen_elevators.iter().cloned().collect()
        } else {
            self.config.elevators.clone()
        }
    }

    pub fn fleet(&self) -> Vec<String> {
        let mut v: Vec<String> = self.seen_elevators.iter().cloned().collect();
        v.sort_by(|a, b| crate::natural_key(a).cmp(&crate::natural_key(b)));
        v
    }

    /// Tabs to show: every view, minus Stats when BI is disabled (via /api/config).
    pub fn visible_views(&self) -> Vec<View> {
        View::ALL
            .into_iter()
            .filter(|v| *v != View::Stats || self.config.bi_enabled)
            .collect()
    }

    fn cycle_view(&mut self, delta: isize) {
        let views = self.visible_views();
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
            View::Chart | View::Trend | View::Stats => self.on_key_elevator_filter(key),
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
        if matches!(key.code, KeyCode::Char('r') | KeyCode::Char('R')) {
            self.message = "restarting elevator-app…".to_string();
            let slot = Arc::clone(&self.k8s_action);
            std::thread::spawn(move || {
                let result =
                    super::k8s::restart().unwrap_or_else(|e| format!("restart failed: {e}"));
                if let Ok(mut g) = slot.lock() {
                    *g = Some(result);
                }
            });
            return;
        }
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
        let max_floor = self.config.max_floor;
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
                &api_sim, count, threads, &pool, max_floor, &sent_t, pace, run_id,
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
        match parse_sim_count(line) {
            Ok(None) => String::new(),
            Ok(Some(count)) => {
                if !self.config.is_known() {
                    return "waiting for /api/config… (limits not loaded yet)".to_string();
                }
                self.start_sim(count);
                format!("running {count} orders")
            }
            Err(msg) => msg.to_string(),
        }
    }

    fn run_order(&mut self, line: &str) -> String {
        match parse_order(line) {
            Ok(None) => String::new(),
            Ok(Some((elevator, floor))) => {
                if let Err(msg) = self.config.validate_order(&elevator, floor) {
                    return msg;
                }
                let (api_base, name) = (self.api_base.clone(), elevator.clone());
                std::thread::spawn(move || {
                    let _ = crate::sender::send_one(&api_base, &name, floor);
                });
                format!("ordered {elevator} → floor {floor}")
            }
            Err(msg) => msg.to_string(),
        }
    }
}

/// Parse the Sim input line. `Ok(None)` = nothing to do (blank); `Ok(Some(n))` = run `n` orders;
/// `Err(msg)` = a hint to show the user. An optional leading `sim` prefix is tolerated.
fn parse_sim_count(line: &str) -> Result<Option<u64>, &'static str> {
    let line = line.trim();
    if line.is_empty() {
        return Ok(None);
    }
    let rest = line
        .strip_prefix("sim")
        .or_else(|| line.strip_prefix("SIM"))
        .map(str::trim)
        .unwrap_or(line);
    match rest.parse::<u64>() {
        Ok(count) if count > 0 => Ok(Some(count)),
        Ok(_) => Err("count must be greater than 0"),
        Err(_) => Err("type a number of orders, e.g. 300"),
    }
}

/// Parse the Order input line into `(elevator, floor)`. `Ok(None)` = blank; `Err(msg)` = a hint.
fn parse_order(line: &str) -> Result<Option<(String, i32)>, &'static str> {
    let parts: Vec<&str> = line.split_whitespace().collect();
    match parts.as_slice() {
        [] => Ok(None),
        [elevator, floor] => match floor.parse::<i32>() {
            Ok(f) => Ok(Some((elevator.to_string(), f))),
            Err(_) => Err("floor must be a number"),
        },
        _ => Err("type:  <elevator> <floor>   e.g.  e3 7"),
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
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_order_accepts_elevator_and_floor() {
        assert_eq!(parse_order("e3 7"), Ok(Some(("e3".to_string(), 7))));
        assert_eq!(parse_order("  e3   7 "), Ok(Some(("e3".to_string(), 7))));
        assert_eq!(parse_order("e3 -1"), Ok(Some(("e3".to_string(), -1))));
    }

    #[test]
    fn parse_order_rejects_bad_input() {
        assert_eq!(parse_order(""), Ok(None));
        assert!(parse_order("e3 up").is_err());
        assert!(parse_order("e3 7 extra").is_err());
    }

    #[test]
    fn parse_sim_count_reads_number_with_optional_prefix() {
        assert_eq!(parse_sim_count("300"), Ok(Some(300)));
        assert_eq!(parse_sim_count("sim 300"), Ok(Some(300)));
        assert_eq!(parse_sim_count("SIM50"), Ok(Some(50)));
        assert_eq!(parse_sim_count(""), Ok(None));
    }

    #[test]
    fn parse_sim_count_rejects_zero_and_junk() {
        assert!(parse_sim_count("0").is_err());
        assert!(parse_sim_count("lots").is_err());
    }

    #[test]
    fn stats_tab_visible_only_when_bi_enabled() {
        let mut app = App::new("http://localhost:8080");
        app.config.bi_enabled = true;
        assert!(app.visible_views().contains(&View::Stats));
        assert_eq!(app.visible_views().len(), View::ALL.len());

        app.config.bi_enabled = false;
        assert!(!app.visible_views().contains(&View::Stats));
        assert_eq!(app.visible_views().len(), View::ALL.len() - 1);
        // the other tabs are all still there, in order
        assert_eq!(
            app.visible_views(),
            View::ALL[..View::ALL.len() - 1].to_vec()
        );
    }

    fn mileage(name: &str, floors: i64) -> MileageRow {
        MileageRow {
            elevator_name: name.to_string(),
            floors_travelled: floors,
        }
    }

    fn served(name: &str, n: i64) -> ServedRow {
        ServedRow {
            elevator_name: name.to_string(),
            orders_served: n,
        }
    }

    #[test]
    fn merge_stats_joins_by_name_and_sorts_by_mileage_desc() {
        let rows = merge_stats(
            vec![mileage("e2", 30), mileage("e10", 5), mileage("e1", 30)],
            vec![served("e2", 7), served("e10", 2), served("e1", 9)],
        );
        let names: Vec<&str> = rows.iter().map(|r| r.name.as_str()).collect();
        // e1 and e2 tie at 30 → natural name order (e1 before e2); e10 last.
        assert_eq!(names, vec!["e1", "e2", "e10"]);
        assert_eq!(rows[0].mileage, 30);
        assert_eq!(rows[0].served, 9);
    }

    #[test]
    fn merge_stats_keeps_elevators_present_in_only_one_list() {
        let rows = merge_stats(vec![mileage("e1", 12)], vec![served("e2", 4)]);
        let e1 = rows.iter().find(|r| r.name == "e1").unwrap();
        let e2 = rows.iter().find(|r| r.name == "e2").unwrap();
        assert_eq!((e1.mileage, e1.served), (12, 0));
        assert_eq!((e2.mileage, e2.served), (0, 4));
    }

    #[test]
    fn merge_stats_empty_inputs_give_empty_rows() {
        assert!(merge_stats(Vec::new(), Vec::new()).is_empty());
    }

    #[test]
    fn elevator_state_deserializes_from_api_json() {
        let json = r#"{"elevatorName":"e2","direction":"UP","motion":"MOVING","floor":4}"#;
        let s: ElevatorState = serde_json::from_str(json).unwrap();
        assert_eq!(s.elevator_name, "e2");
        assert_eq!(s.floor, 4);
        assert_eq!(s.direction, "UP");
    }

    fn sim_run(total: u64, sent: u64, checked: u64, done: u64, progress: u64) -> SimRun {
        SimRun {
            total,
            elevators: 1,
            sent: Arc::new(AtomicU64::new(sent)),
            done: Arc::new(AtomicBool::new(false)),
            start: Instant::now(),
            elapsed: None,
            checked,
            status_done: Arc::new(AtomicU64::new(done)),
            status_progress: Arc::new(AtomicU64::new(progress)),
        }
    }

    #[test]
    fn sim_ratio_is_clamped() {
        assert_eq!(sim_run(0, 0, 0, 0, 0).ratio(), 0.0);
        assert_eq!(sim_run(100, 50, 0, 0, 0).ratio(), 0.5);
        assert_eq!(sim_run(100, 999, 0, 0, 0).ratio(), 1.0);
    }

    #[test]
    fn sim_pending_never_underflows() {
        // done + in_progress may briefly exceed `checked`; pending must saturate at 0, not wrap.
        assert_eq!(sim_run(10, 10, 8, 5, 3).pending(), 0);
        assert_eq!(sim_run(10, 10, 8, 9, 9).pending(), 0);
        assert_eq!(sim_run(10, 10, 10, 4, 3).pending(), 3);
    }
}
