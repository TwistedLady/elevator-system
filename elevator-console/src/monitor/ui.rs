//! Rendering only. Reads `App` and draws the current view; never mutates state.

use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Style, Stylize};
use ratatui::symbols::Marker;
use ratatui::text::{Line, Span};
use ratatui::widgets::{
    Axis, Block, BorderType, Chart, Dataset, Gauge, GraphType, Paragraph, Tabs, Wrap,
};
use ratatui::Frame;

use super::app::{App, ElevatorState, View, TREND_WINDOW_SECS};

/// Line colors cycled across elevators in the trend chart.
const PALETTE: [Color; 7] = [
    Color::Green,
    Color::Cyan,
    Color::Magenta,
    Color::Yellow,
    Color::Blue,
    Color::Red,
    Color::White,
];

/// Column width per elevator in the chart.
const COL_W: usize = 8;

pub fn draw(frame: &mut Frame, app: &App) {
    let chunks = Layout::vertical([
        Constraint::Length(3), // tab bar
        Constraint::Min(0),    // active view
        Constraint::Length(3), // footer / input
    ])
    .split(frame.area());

    draw_tabs(frame, app, chunks[0]);
    match app.view {
        View::Chart => draw_chart(frame, app, chunks[1]),
        View::Trend => draw_trend(frame, app, chunks[1]),
        View::Order => draw_order(frame, app, chunks[1]),
        View::Sim => draw_sim(frame, app, chunks[1]),
        View::Health => draw_health(frame, app, chunks[1]),
        View::Logs => draw_logs(frame, app, chunks[1]),
    }
    draw_footer(frame, app, chunks[2]);
}

fn retro_block(title: &str) -> Block<'_> {
    Block::bordered()
        .border_type(BorderType::Double)
        .border_style(Style::new().fg(Color::Green))
        .title(Span::from(title).yellow().bold())
}

fn draw_tabs(frame: &mut Frame, app: &App, area: Rect) {
    let titles: Vec<Line> = View::ALL.iter().map(|v| Line::from(v.title())).collect();
    // Live local clock in the header, right-aligned — visible on every tab, so you can read the
    // time when the SIM bar hits 100% vs when the TREND chart settles and measure the lag.
    let clock = retro_block(" ▌ ELEVATOR CONTROL ▐ ")
        .title_top(Line::from(format!(" ⏱ {} ", app.now_clock())).cyan().bold().right_aligned());
    let tabs = Tabs::new(titles)
        .select(app.view.index())
        .block(clock)
        .style(Style::new().fg(Color::Green))
        .highlight_style(Style::new().fg(Color::Black).bg(Color::Yellow).bold())
        .divider(Span::from("│").dark_gray());
    frame.render_widget(tabs, area);
}

fn draw_chart(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" BUILDING ");
    // Backend unreachable, or reachable but no state yet -> show a clear waiting banner.
    if !app.health.reachable || app.latest.is_empty() {
        frame.render_widget(Paragraph::new(waiting_line(app)).block(block), area);
        return;
    }

    let mut cars: Vec<&ElevatorState> = app
        .latest
        .values()
        .filter(|c| app.elevator_matches(&c.elevator_name))
        .collect();
    if cars.is_empty() {
        let msg = format!("no elevators match /{}/", app.elevator_filter);
        frame.render_widget(Paragraph::new(msg.dim()).block(block), area);
        return;
    }
    // Natural order so columns read e1, e2, … e10 (not the lexicographic e1, e10, e2).
    cars.sort_by(|a, b| crate::natural_key(&a.elevator_name).cmp(&crate::natural_key(&b.elevator_name)));
    let top = cars.iter().map(|c| c.floor).max().unwrap_or(0).max(0);
    let bottom = cars.iter().map(|c| c.floor).min().unwrap_or(0).min(0);

    let mut lines: Vec<Line> = Vec::new();

    // Header: floor-axis label + elevator names.
    let mut header = vec![Span::from(format!("{:>4} ", "Fl")).dark_gray()];
    for c in &cars {
        header.push(Span::from(center(&c.elevator_name, COL_W)).cyan());
    }
    lines.push(Line::from(header));

    for floor in (bottom..=top).rev() {
        let mut spans = vec![Span::from(format!("{floor:>4} ")).dark_gray()];
        for c in &cars {
            if c.floor == floor {
                let cell = center(&format!("[{}]", car_glyph(&c.direction, &c.motion)), COL_W);
                spans.push(Span::styled(cell, car_style(&c.direction, &c.motion)));
            } else {
                spans.push(Span::from(center("·", COL_W)).dark_gray());
            }
        }
        lines.push(Line::from(spans));
    }

    frame.render_widget(Paragraph::new(lines).block(block), area);
}

fn draw_trend(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" FLOOR OVER TIME ");
    if !app.health.reachable || app.history.is_empty() {
        frame.render_widget(Paragraph::new(waiting_line(app)).block(block), area);
        return;
    }

    // Scroll continuously with real time (EKG-style): the right edge is "now", so idle cars draw
    // flat horizontal lines that keep moving left. The x-axis is labelled with local clock time
    // (below), so you still read exactly when each point is from.
    let x_hi = app.now_secs();
    let x_lo = x_hi - TREND_WINDOW_SECS;

    // Own the point vectors locally so the datasets can borrow them for this frame.
    // Each line is held flat to the right edge (now) at the elevator's current floor, so an idle
    // car shows a horizontal line scrolling left instead of stopping at its last update.
    let mut series: Vec<(String, Vec<(f64, f64)>)> = app
        .history
        .iter()
        .filter(|(name, _)| app.elevator_matches(name))
        .map(|(name, pts)| {
            let mut v: Vec<(f64, f64)> = pts.iter().copied().collect();
            if let Some(state) = app.latest.get(name) {
                v.push((x_hi, state.floor as f64));
            }
            (name.clone(), v)
        })
        .collect();
    if series.is_empty() {
        let msg = format!("no elevators match /{}/", app.elevator_filter);
        frame.render_widget(Paragraph::new(msg.dim()).block(block), area);
        return;
    }
    // Natural order so the legend reads e1, e2, … e10 and colours stay stable.
    series.sort_by(|a, b| crate::natural_key(&a.0).cmp(&crate::natural_key(&b.0)));

    // Y range from the data, padded so a flat line isn't on the border.
    let mut y_lo = f64::MAX;
    let mut y_hi = f64::MIN;
    for (_, pts) in &series {
        for &(_, y) in pts {
            y_lo = y_lo.min(y);
            y_hi = y_hi.max(y);
        }
    }
    if !y_lo.is_finite() || !y_hi.is_finite() {
        y_lo = 0.0;
        y_hi = 15.0;
    }
    if (y_hi - y_lo).abs() < 1.0 {
        y_lo -= 1.0;
        y_hi += 1.0;
    }

    let datasets: Vec<Dataset> = series
        .iter()
        .enumerate()
        .map(|(i, (name, pts))| {
            Dataset::default()
                .name(name.clone())
                .marker(Marker::Braille)
                .graph_type(GraphType::Line)
                .style(Style::new().fg(PALETTE[i % PALETTE.len()]))
                .data(pts)
        })
        .collect();

    let chart = Chart::new(datasets)
        .block(block)
        .x_axis(
            Axis::default()
                .style(Style::new().dark_gray())
                .bounds([x_lo, x_hi])
                // Local wall-clock time of the data at each x-position (left / middle / right edge).
                .labels([
                    Span::from(app.clock_at(x_lo)),
                    Span::from(app.clock_at((x_lo + x_hi) / 2.0)),
                    Span::from(app.clock_at(x_hi)),
                ]),
        )
        .y_axis(
            Axis::default()
                .title("floor")
                .style(Style::new().dark_gray())
                .bounds([y_lo, y_hi])
                .labels([Span::from(format!("{y_lo:.0}")), Span::from(format!("{y_hi:.0}"))]),
        );

    frame.render_widget(chart, area);
}

fn draw_order(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" ORDER ");
    let mut lines: Vec<Line> = vec![
        Line::from("Send one elevator to a floor.".white()),
        Line::from("Type below:  <elevator> <floor>   e.g.  e3 7".dim()),
        Line::from(""),
    ];
    if !app.message.is_empty() {
        lines.push(Line::from(app.message.clone().cyan()));
        lines.push(Line::from(""));
    }
    // Known elevators (natural order) as a hint of valid names.
    let mut names: Vec<&String> = app.latest.keys().collect();
    names.sort_by(|a, b| crate::natural_key(a).cmp(&crate::natural_key(b)));
    let known = if names.is_empty() {
        "(none seen yet)".to_string()
    } else {
        names.iter().map(|s| s.as_str()).collect::<Vec<_>>().join(", ")
    };
    lines.push(Line::from(vec![
        Span::from("known elevators: ").dark_gray(),
        Span::from(known).green(),
    ]));

    frame.render_widget(Paragraph::new(lines).block(block).wrap(Wrap { trim: false }), area);
}

fn draw_sim(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" SIMULATOR ");
    let inner = block.inner(area);
    frame.render_widget(block, area);

    let rows = Layout::vertical([
        Constraint::Length(3), // status text
        Constraint::Length(1), // "sent" gauge
        Constraint::Length(1), // labels
        Constraint::Length(1), // "processed" gauge (verified via the API)
        Constraint::Min(0),    // help
    ])
    .split(inner);

    match &app.sim {
        None => {
            let p = Paragraph::new(vec![
                Line::from("No simulation running.".dim()),
                Line::from("Type how many orders to fire below, then press Enter.".dim()),
            ]);
            frame.render_widget(p, rows[0]);
        }
        Some(sim) => {
            let sent = sim.sent();
            let secs = sim.secs();
            let rate = if secs > 0.0 { sent as f64 / secs } else { 0.0 };
            let status = if sim.finished() {
                "done ✓".green().bold()
            } else {
                "running…".yellow().bold()
            };
            let info = Paragraph::new(vec![
                Line::from(vec![Span::from("status   ").white(), status]),
                Line::from(
                    format!(
                        "{sent}/{} orders   {rate:.0} msg/s   {secs:.2}s   across {} elevators",
                        sim.total, sim.elevators
                    )
                    .dark_gray(),
                ),
            ]);
            frame.render_widget(info, rows[0]);

            // "sent" gauge — how many orders reached Kafka.
            let sent_pct = (sim.ratio() * 100.0).round() as u16;
            let sent_fill = if sim.finished() { Color::Green } else { Color::Cyan };
            frame.render_widget(
                Gauge::default()
                    .gauge_style(Style::new().fg(sent_fill).bg(Color::Black).bold())
                    .ratio(sim.ratio())
                    .label(format!("sent  {sent}/{}  ({sent_pct}%)", sim.total)),
                rows[1],
            );

            // Per-status verification (polled from the API until all DONE): a single bar split
            // into DONE (green) · PROGRESS (yellow) · not-yet-seen (grey).
            let total = sim.checked.max(1);
            let done = sim.done();
            let prog = sim.in_progress();
            let pending = sim.pending();
            frame.render_widget(
                Paragraph::new(
                    format!(
                        "order status (API):  DONE {done}  ·  PROGRESS {prog}  ·  pending {pending}   / {}",
                        sim.checked
                    )
                    .dark_gray(),
                ),
                rows[2],
            );

            let bar_w = rows[3].width as usize;
            let done_w = (bar_w as u64 * done / total) as usize;
            let prog_w = (bar_w as u64 * prog / total) as usize;
            let rest_w = bar_w.saturating_sub(done_w + prog_w);
            let bar = Line::from(vec![
                Span::styled("█".repeat(done_w), Style::new().fg(Color::Green)),
                Span::styled("█".repeat(prog_w), Style::new().fg(Color::Yellow)),
                Span::styled("░".repeat(rest_w), Style::new().fg(Color::DarkGray)),
            ]);
            frame.render_widget(Paragraph::new(bar), rows[3]);
        }
    }
}

fn draw_health(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" ACTUATOR · /actuator/health ");
    let mut lines: Vec<Line> = Vec::new();

    if !app.health.reachable {
        lines.push(Line::from("api unreachable — is elevator-api running on :8080?".red()));
    }

    let ostyle = status_style(&app.health.overall);
    lines.push(Line::from(vec![
        Span::from("overall   ").white(),
        Span::styled(format!("{} ", status_icon(&app.health.overall)), ostyle),
        Span::styled(app.health.overall.clone(), ostyle.bold()),
    ]));
    lines.push(Line::from(""));

    for c in &app.health.components {
        let st = status_style(&c.status);
        lines.push(Line::from(vec![
            Span::styled(format!("{} ", status_icon(&c.status)), st),
            Span::from(format!("{:<14}", c.name)).white(),
            Span::styled(format!("{:<6}", c.status), st),
            Span::from(c.detail.clone()).dark_gray(),
        ]));
    }

    frame.render_widget(Paragraph::new(lines).block(block), area);
}

fn draw_logs(frame: &mut Frame, app: &App, area: Rect) {
    // Apply the regex filter (if any) to the selected source's lines.
    let matched: Vec<&String> = match &app.log_re {
        Some(re) => app.logs().iter().filter(|l| re.is_match(l)).collect(),
        None => app.logs().iter().collect(),
    };

    let filter_note = if app.log_filter.is_empty() {
        String::new()
    } else {
        format!(" · /{}/", app.log_filter)
    };
    let title = format!(
        " LOGS · {}   {} lines{}   (←/→ source) ",
        app.log_source.label(),
        matched.len(),
        filter_note
    );
    let block = retro_block(&title);

    // Wrap long lines to the panel width so nothing is cut off at the border, then keep only the
    // last `visible` rows so the newest output stays at the bottom. Wrap from the bottom up so we
    // never process more than a screenful of lines.
    let inner_w = area.width.saturating_sub(2).max(1) as usize;
    let visible = area.height.saturating_sub(2) as usize;

    let mut rows: Vec<Line> = Vec::new();
    for l in matched.iter().rev() {
        let mut wrapped = wrap_log_line(l, inner_w);
        wrapped.append(&mut rows);
        rows = wrapped;
        if rows.len() >= visible {
            break;
        }
    }
    let start = rows.len().saturating_sub(visible);
    let shown: Vec<Line> = rows.split_off(start);

    frame.render_widget(Paragraph::new(shown).block(block), area);
}

fn draw_footer(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block("");
    let content = match app.view {
        View::Chart | View::Trend => {
            let mut spans = vec![
                Span::from("filter ▸ ").green().bold(),
                Span::from(app.elevator_filter.clone()).white(),
                Span::from("█").green(),
            ];
            if app.elevator_re_err {
                spans.push(Span::from("  invalid regex").red());
            }
            spans.push(Span::from("   Enter: clear · Tab: switch · Esc: quit").dark_gray());
            Line::from(spans)
        }
        View::Order => {
            let mut spans = vec![
                Span::from("order ▸ ").green().bold(),
                Span::from(app.order_input.clone()).white(),
                Span::from("█").green(),
                Span::from("   <elevator> <floor> · Enter: send").dark_gray(),
            ];
            if !app.message.is_empty() {
                spans.push(Span::from(format!("   {}", app.message)).cyan());
            }
            Line::from(spans)
        }
        View::Sim => {
            let mut spans = vec![
                Span::from("sim ▸ ").green().bold(),
                Span::from(app.sim_input.clone()).white(),
                Span::from("█").green(),
                Span::from("   number of orders · Enter: run").dark_gray(),
            ];
            if !app.message.is_empty() {
                spans.push(Span::from(format!("   {}", app.message)).cyan());
            }
            Line::from(spans)
        }
        View::Logs => {
            let mut spans = vec![
                Span::from("filter ▸ ").green().bold(),
                Span::from(app.log_filter.clone()).white(),
                Span::from("█").green(),
            ];
            if app.log_re_err {
                spans.push(Span::from("  invalid regex").red());
            }
            spans.push(Span::from("   Enter: clear · ←/→: source · Esc: quit").dark_gray());
            Line::from(spans)
        }
        View::Health => Line::from(
            "Tab/Shift-Tab: switch view   ·   Esc: quit".dim(),
        ),
    };
    frame.render_widget(Paragraph::new(content).block(block).wrap(Wrap { trim: false }), area);
}

// ---- small helpers --------------------------------------------------------

/// Banner for the data views: tells "backend is down" apart from "backend up, no traffic yet".
fn waiting_line(app: &App) -> Line<'static> {
    if !app.health.reachable {
        Line::from("⏳  waiting for backend — elevator-api unreachable on :8080".yellow())
    } else {
        Line::from("waiting for elevator state…".dim())
    }
}

/// Colour for a log line, by severity keyword.
fn log_style(s: &str) -> Style {
    let upper = s.to_ascii_uppercase();
    if upper.contains("ERROR") {
        Style::new().fg(Color::Red)
    } else if upper.contains("WARN") {
        Style::new().fg(Color::Yellow)
    } else {
        Style::new().fg(Color::Gray)
    }
}

/// Render one log line for the Logs view: shorten its thread name, then hard-wrap it to `width`
/// columns so long lines aren't cut off at the border. One styled row per wrapped chunk.
fn wrap_log_line(s: &str, width: usize) -> Vec<Line<'static>> {
    let text = shorten_thread(s);
    let style = log_style(&text);
    let chars: Vec<char> = text.chars().collect();
    if chars.is_empty() {
        return vec![Line::from("")];
    }
    chars
        .chunks(width.max(1))
        .map(|chunk| Line::styled(chunk.iter().collect::<String>(), style))
        .collect()
}

/// Display-only: shorten the thread name in the first `[...]` so lines fit the narrow Logs view.
/// e.g. `[elevator-cluster-pekko.actor.default-dispatcher-18]` -> `[e-c-p.a.d-d-18]`.
/// Each run of letters collapses to its first letter; digits and the `-`/`.`/`_` separators stay.
fn shorten_thread(s: &str) -> String {
    let (Some(open), Some(close)) = (s.find('['), s.find(']')) else {
        return s.to_string();
    };
    if close <= open + 1 {
        return s.to_string();
    }
    let short = abbreviate(&s[open + 1..close]);
    format!("{}[{}]{}", &s[..open], short, &s[close + 1..])
}

fn abbreviate(name: &str) -> String {
    let mut out = String::new();
    let mut chars = name.chars().peekable();
    while let Some(c) = chars.next() {
        if c.is_alphabetic() {
            out.push(c); // keep the first letter of the run, drop the rest
            while chars.peek().is_some_and(|n| n.is_alphabetic()) {
                chars.next();
            }
        } else {
            out.push(c); // digits and separators pass through
        }
    }
    out
}

fn status_icon(status: &str) -> &'static str {
    match status.to_ascii_uppercase().as_str() {
        "UP" => "●",
        "DOWN" => "✖",
        _ => "?",
    }
}

fn status_style(status: &str) -> Style {
    match status.to_ascii_uppercase().as_str() {
        "UP" => Style::new().fg(Color::Green),
        "DOWN" => Style::new().fg(Color::Red),
        _ => Style::new().fg(Color::Yellow),
    }
}

fn car_glyph(direction: &str, motion: &str) -> char {
    if !motion.eq_ignore_ascii_case("moving") {
        return '•';
    }
    match direction.to_ascii_uppercase().as_str() {
        "UP" => '↑',
        "DOWN" => '↓',
        _ => '•',
    }
}

fn car_style(direction: &str, motion: &str) -> Style {
    if !motion.eq_ignore_ascii_case("moving") {
        return Style::new().fg(Color::DarkGray);
    }
    match direction.to_ascii_uppercase().as_str() {
        "UP" => Style::new().fg(Color::Green).bold(),
        "DOWN" => Style::new().fg(Color::Magenta).bold(),
        _ => Style::new().fg(Color::White),
    }
}

/// Center `s` within `w` columns (counts chars so arrows/dots align).
fn center(s: &str, w: usize) -> String {
    let len = s.chars().count();
    if len >= w {
        return s.chars().take(w).collect();
    }
    let pad = w - len;
    let left = pad / 2;
    format!("{}{}{}", " ".repeat(left), s, " ".repeat(pad - left))
}
