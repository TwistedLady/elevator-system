//! Monitor TUI rendering: header, footer, and the Chart, Trend, and Sim views.
use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Style, Stylize};
use ratatui::symbols::Marker;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Axis, Block, BorderType, Chart, Dataset, GraphType, Paragraph, Tabs, Wrap};
use ratatui::Frame;

use super::app::{App, ElevatorState, View, TREND_WINDOW_SECS};

const PALETTE: [Color; 7] = [
    Color::Green,
    Color::Cyan,
    Color::Magenta,
    Color::Yellow,
    Color::Blue,
    Color::Red,
    Color::White,
];

const COL_W: usize = 8;

pub fn draw(frame: &mut Frame, app: &App) {
    let chunks = Layout::vertical([
        Constraint::Length(3),
        Constraint::Min(0),
        Constraint::Length(3),
    ])
    .split(frame.area());

    draw_header(frame, app, chunks[0]);
    match app.view {
        View::Chart => draw_chart(frame, app, chunks[1]),
        View::Trend => draw_trend(frame, app, chunks[1]),
        View::Sim => draw_sim(frame, app, chunks[1]),
    }
    draw_footer(frame, app, chunks[2]);
}

fn retro_block(title: &str) -> Block<'_> {
    Block::bordered()
        .border_type(BorderType::Double)
        .border_style(Style::new().fg(Color::Green))
        .title(Span::from(title).yellow().bold())
}

fn health_badge(reachable: bool, overall: &str) -> (&'static str, Color) {
    if reachable && overall.eq_ignore_ascii_case("UP") {
        ("API UP", Color::Green)
    } else if !reachable && overall == "?" {
        ("API …", Color::Yellow)
    } else {
        ("API DOWN", Color::Red)
    }
}

fn version_badge(console: &str, backend: Option<&str>) -> (String, Color) {
    match backend {
        None => (format!("v{console} · api …"), Color::Yellow),
        Some(b) if b == console => (format!("v{console} = api {b}"), Color::Green),
        Some(b) => (format!("v{console} ≠ api {b}"), Color::Red),
    }
}

fn draw_header(frame: &mut Frame, app: &App, area: Rect) {
    let views = View::ALL;
    let titles: Vec<Line> = views.iter().map(|v| Line::from(v.title())).collect();

    let (htxt, hcolor) = health_badge(app.health.reachable, &app.health.overall);
    let (vtxt, vcolor) = version_badge(
        crate::version::CONSOLE_VERSION,
        app.backend_version.as_deref(),
    );
    let badges = Line::from(vec![
        Span::from(format!(" {htxt} "))
            .fg(Color::Black)
            .bg(hcolor)
            .bold(),
        Span::from("  "),
        Span::from(format!(" {vtxt} ")).fg(vcolor).bold(),
    ])
    .right_aligned();

    let header = Block::bordered()
        .border_type(BorderType::Double)
        .border_style(Style::new().fg(Color::Cyan))
        .title(Span::from(" 🛗 ELEVATOR CONSOLE ").cyan().bold())
        .title_top(badges);

    let tabs = Tabs::new(titles)
        .select(views.iter().position(|v| *v == app.view).unwrap_or(0))
        .block(header)
        .style(Style::new().fg(Color::Green))
        .highlight_style(Style::new().fg(Color::Black).bg(Color::Cyan).bold())
        .divider(Span::from("│").dark_gray());
    frame.render_widget(tabs, area);
}

fn draw_chart(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" BUILDING ");
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
    cars.sort_by(|a, b| {
        crate::natural_key(&a.elevator_name).cmp(&crate::natural_key(&b.elevator_name))
    });
    let top = cars.iter().map(|c| c.floor).max().unwrap_or(0).max(0);
    let bottom = cars.iter().map(|c| c.floor).min().unwrap_or(0).min(0);

    let mut lines: Vec<Line> = Vec::new();

    let mut header = vec![Span::from(format!("{:>4} ", "Fl")).dark_gray()];
    for c in &cars {
        header.push(Span::from(center(&c.elevator_name, COL_W)).cyan());
    }
    lines.push(Line::from(header));

    for floor in (bottom..=top).rev() {
        let mut spans = vec![Span::from(format!("{floor:>4} ")).dark_gray()];
        for c in &cars {
            if c.floor == floor {
                let open = app
                    .doors
                    .get(&c.elevator_name)
                    .map(|d| d.door_state.eq_ignore_ascii_case("open"))
                    .unwrap_or(false);
                let door = if open { 'o' } else { 'x' };
                let cell = center(
                    &format!("[{}][{}]", car_glyph(&c.direction, &c.motion), door),
                    COL_W,
                );
                let style = if open {
                    Style::new().fg(Color::Yellow).bold()
                } else {
                    car_style(&c.direction, &c.motion)
                };
                spans.push(Span::styled(cell, style));
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

    let x_hi = app.now_secs();
    let x_lo = x_hi - TREND_WINDOW_SECS;

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
    series.sort_by(|a, b| crate::natural_key(&a.0).cmp(&crate::natural_key(&b.0)));

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
        y_hi = if app.config.max_floor > 0 {
            app.config.max_floor as f64
        } else {
            10.0
        };
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
                .labels([
                    Span::from(format!("{y_lo:.0}")),
                    Span::from(format!("{y_hi:.0}")),
                ]),
        );

    frame.render_widget(chart, area);
}

fn draw_sim(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" SIMULATOR ");
    let inner = block.inner(area);
    frame.render_widget(block, area);

    let rows = Layout::vertical([
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Min(0),
    ])
    .split(inner);

    let Some(sim) = &app.sim else {
        frame.render_widget(
            Paragraph::new(Line::from(vec![
                Span::from("Press ").dim(),
                Span::from(" R ").fg(Color::Black).bg(Color::Cyan).bold(),
                Span::from(" to run a 10,000-call simulation").dim(),
            ])),
            rows[0],
        );
        return;
    };

    if let Some(err) = sim.error() {
        frame.render_widget(Paragraph::new(Line::from(err.red())), rows[0]);
        return;
    }

    let run_id = sim.run_id();
    let (done, prog, pending) = (sim.done(), sim.in_progress(), sim.pending());

    let status = if sim.complete() {
        Line::from("✓ simulation complete".green().bold())
    } else if run_id.is_empty() {
        Line::from("starting…".yellow().bold())
    } else {
        Line::from("running…".yellow().bold())
    };
    frame.render_widget(Paragraph::new(status), rows[0]);

    let (done_w, prog_w, rest_w) = split_bar(done, prog, pending, rows[1].width as usize);
    let bar = Line::from(vec![
        Span::styled("█".repeat(done_w), Style::new().fg(Color::Green)),
        Span::styled("█".repeat(prog_w), Style::new().fg(Color::Yellow)),
        Span::styled("░".repeat(rest_w), Style::new().fg(Color::DarkGray)),
    ]);
    frame.render_widget(Paragraph::new(bar), rows[1]);

    let counts = format!(
        "run {} · size {} · calls {} · done {done} · progress {prog} · pending {pending}",
        if run_id.is_empty() { "…" } else { &run_id },
        sim.size(),
        sim.calls(),
    );
    frame.render_widget(Paragraph::new(counts.dark_gray()), rows[2]);

    let meta = format!(
        "orders {} · first {} · last {}",
        sim.orders(),
        hms(sim.first_call()),
        hms(sim.last_done()),
    );
    frame.render_widget(Paragraph::new(meta.dark_gray()), rows[3]);
}

fn hms(iso: Option<String>) -> String {
    match iso {
        Some(s) => s
            .split('T')
            .nth(1)
            .map(|t| t.chars().take(8).collect())
            .unwrap_or(s),
        None => "—".to_string(),
    }
}

fn split_bar(done: u64, progress: u64, pending: u64, width: usize) -> (usize, usize, usize) {
    let total = done + progress + pending;
    if total == 0 || width == 0 {
        return (0, 0, width);
    }
    let done_w = (width as u64 * done / total) as usize;
    let prog_w = (width as u64 * progress / total) as usize;
    let rest_w = width.saturating_sub(done_w + prog_w);
    (done_w, prog_w, rest_w)
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
        View::Sim => {
            let mut spans = vec![
                Span::from("R").fg(Color::Black).bg(Color::Cyan).bold(),
                Span::from(": run 10k simulation · Tab: switch · Esc: quit").dark_gray(),
            ];
            if !app.message.is_empty() {
                spans.push(Span::from(format!("   {}", app.message)).cyan());
            }
            Line::from(spans)
        }
    };
    frame.render_widget(
        Paragraph::new(content)
            .block(block)
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn waiting_line(app: &App) -> Line<'static> {
    if !app.health.reachable {
        Line::from("⏳  waiting for backend — elevator-api unreachable on :8080".yellow())
    } else {
        Line::from("waiting for elevator state…".dim())
    }
}

fn car_glyph(direction: &str, motion: &str) -> char {
    if !motion.eq_ignore_ascii_case("moving") {
        return 'X';
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

fn center(s: &str, w: usize) -> String {
    let len = s.chars().count();
    if len >= w {
        return s.chars().take(w).collect();
    }
    let pad = w - len;
    let left = pad / 2;
    format!("{}{}{}", " ".repeat(left), s, " ".repeat(pad - left))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn health_badge_maps_states_to_colours() {
        assert_eq!(health_badge(true, "UP"), ("API UP", Color::Green));
        assert_eq!(health_badge(false, "?"), ("API …", Color::Yellow));
        assert_eq!(health_badge(false, "DOWN"), ("API DOWN", Color::Red));
        assert_eq!(health_badge(true, "DOWN"), ("API DOWN", Color::Red));
    }

    #[test]
    fn version_badge_flags_match_and_mismatch() {
        assert_eq!(
            version_badge("1.2.3", Some("1.2.3")),
            ("v1.2.3 = api 1.2.3".to_string(), Color::Green)
        );
        assert_eq!(
            version_badge("1.2.3", Some("1.2.4")),
            ("v1.2.3 ≠ api 1.2.4".to_string(), Color::Red)
        );
        assert_eq!(version_badge("1.2.3", None).1, Color::Yellow);
    }

    #[test]
    fn split_bar_is_proportional_and_fills_width() {
        let (d, p, r) = split_bar(50, 30, 20, 100);
        assert_eq!((d, p, r), (50, 30, 20));
        let (d, p, r) = split_bar(1, 1, 1, 10);
        assert_eq!(d + p + r, 10);
    }

    #[test]
    fn split_bar_all_pending_when_nothing_counted() {
        assert_eq!(split_bar(0, 0, 0, 20), (0, 0, 20));
        assert_eq!(split_bar(0, 0, 0, 0), (0, 0, 0));
    }
}
