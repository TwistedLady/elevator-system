//! Rendering only. Reads `App` and draws the current view; never mutates state.

use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Style, Stylize};
use ratatui::symbols::Marker;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Axis, Block, BorderType, Chart, Dataset, GraphType, Paragraph, Tabs, Wrap};
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
    let tabs = Tabs::new(titles)
        .select(app.view.index())
        .block(retro_block(" ▌ ELEVATOR CONTROL ▐ "))
        .style(Style::new().fg(Color::Green))
        .highlight_style(Style::new().fg(Color::Black).bg(Color::Yellow).bold())
        .divider(Span::from("│").dark_gray());
    frame.render_widget(tabs, area);
}

fn draw_chart(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" BUILDING ");
    if app.latest.is_empty() {
        let p = Paragraph::new("waiting for elevator state…".dim()).block(block);
        frame.render_widget(p, area);
        return;
    }

    let cars: Vec<&ElevatorState> = app.latest.values().collect();
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
    if app.history.is_empty() {
        let p = Paragraph::new("waiting for elevator state…".dim()).block(block);
        frame.render_widget(p, area);
        return;
    }

    // X window: the last TREND_WINDOW_SECS seconds, scrolling with "now".
    let x_hi = app.now_secs().max(TREND_WINDOW_SECS);
    let x_lo = x_hi - TREND_WINDOW_SECS;

    // Own the point vectors locally so the datasets can borrow them for this frame.
    let series: Vec<(String, Vec<(f64, f64)>)> = app
        .history
        .iter()
        .map(|(name, pts)| (name.clone(), pts.iter().copied().collect()))
        .collect();

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
                .labels([Span::from(format!("{x_lo:.0}s")), Span::from(format!("{x_hi:.0}s"))]),
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

    // Show only the last lines that fit (minus the 2 border rows).
    let visible = area.height.saturating_sub(2) as usize;
    let start = matched.len().saturating_sub(visible);
    let lines: Vec<Line> = matched.iter().skip(start).map(|l| log_line(l)).collect();

    frame.render_widget(Paragraph::new(lines).block(block), area);
}

fn draw_footer(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block("");
    let content = match app.view {
        View::Chart => Line::from(vec![
            Span::from("> ").green().bold(),
            Span::from(app.input.clone()).white(),
            Span::from("█").green(),
            Span::from("   "),
            Span::from(app.message.clone()).dark_gray(),
        ]),
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
        View::Health | View::Trend => Line::from(
            "Tab/Shift-Tab: switch view   ·   Esc: quit".dim(),
        ),
    };
    frame.render_widget(Paragraph::new(content).block(block).wrap(Wrap { trim: false }), area);
}

// ---- small helpers --------------------------------------------------------

fn log_line(s: &str) -> Line<'static> {
    let upper = s.to_ascii_uppercase();
    let style = if upper.contains("ERROR") {
        Style::new().fg(Color::Red)
    } else if upper.contains("WARN") {
        Style::new().fg(Color::Yellow)
    } else {
        Style::new().fg(Color::Gray)
    };
    Line::styled(s.to_string(), style)
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
