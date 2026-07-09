use ratatui::layout::{Constraint, Layout, Rect};
use ratatui::style::{Color, Style, Stylize};
use ratatui::symbols::Marker;
use ratatui::text::{Line, Span};
use ratatui::widgets::{
    Axis, Block, BorderType, Chart, Dataset, Gauge, GraphType, Paragraph, Tabs, Wrap,
};
use ratatui::Frame;

use super::app::{App, ElevatorState, StatsRow, View, TREND_WINDOW_SECS};

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

    draw_tabs(frame, app, chunks[0]);
    match app.view {
        View::Chart => draw_chart(frame, app, chunks[1]),
        View::Trend => draw_trend(frame, app, chunks[1]),
        View::Call => draw_call(frame, app, chunks[1]),
        View::Sim => draw_sim(frame, app, chunks[1]),
        View::Health => draw_health(frame, app, chunks[1]),
        View::Logs => draw_logs(frame, app, chunks[1]),
        View::K8s => draw_k8s(frame, app, chunks[1]),
        View::Test => draw_test(frame, app, chunks[1]),
        View::Stats => draw_stats(frame, app, chunks[1]),
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
    let views = app.visible_views();
    let titles: Vec<Line> = views.iter().map(|v| Line::from(v.title())).collect();
    let (mode_txt, mode_color) = match app.k8s.mode.as_str() {
        "fast" => ("FAST", Color::Cyan),
        "slow" => ("SLOW", Color::Magenta),
        _ => ("MODE ?", Color::DarkGray),
    };
    let git = app.git.label();
    let header = retro_block(" ▌ ELEVATOR CONTROL ▐ ").title_top(
        Line::from(vec![
            Span::from(if git.is_empty() {
                String::new()
            } else {
                format!(" {git} ")
            })
            .dark_gray(),
            Span::from(format!(" {mode_txt} "))
                .fg(Color::Black)
                .bg(mode_color)
                .bold(),
            Span::from(format!(" ⏱ {} ", app.now_clock())).cyan().bold(),
        ])
        .right_aligned(),
    );
    let clock = header;
    let tabs = Tabs::new(titles)
        .select(views.iter().position(|v| *v == app.view).unwrap_or(0))
        .block(clock)
        .style(Style::new().fg(Color::Green))
        .highlight_style(Style::new().fg(Color::Black).bg(Color::Yellow).bold())
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

fn draw_call(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" CALL ");
    let mut lines: Vec<Line> = vec![
        Line::from("Call one elevator to a floor.".white()),
        Line::from("Type below:  <elevator> <floor>   e.g.  e3 7".dim()),
        Line::from(""),
    ];
    if !app.message.is_empty() {
        lines.push(Line::from(app.message.clone().cyan()));
        lines.push(Line::from(""));
    }
    let fleet = app.fleet();
    let known = if fleet.is_empty() {
        "(none seen yet)".to_string()
    } else {
        fleet.join(", ")
    };
    lines.push(Line::from(vec![
        Span::from("fleet (session): ").dark_gray(),
        Span::from(known).green(),
    ]));
    lines.push(Line::from(vec![
        Span::from("floors seen:     ").dark_gray(),
        Span::from(format!("0..{}", app.seen_floor_max)).green(),
    ]));

    frame.render_widget(
        Paragraph::new(lines)
            .block(block)
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn draw_sim(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" SIMULATOR ");
    let inner = block.inner(area);
    frame.render_widget(block, area);

    let rows = Layout::vertical([
        Constraint::Length(3),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Length(1),
        Constraint::Min(0),
    ])
    .split(inner);

    match &app.sim {
        None => {
            let p = Paragraph::new(vec![
                Line::from("No simulation running.".dim()),
                Line::from("Type how many calls to fire below, then press Enter.".dim()),
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
                        "{sent}/{} calls   {rate:.0} msg/s   {secs:.2}s   across {} elevators",
                        sim.total, sim.elevators
                    )
                    .dark_gray(),
                ),
            ]);
            frame.render_widget(info, rows[0]);

            let sent_pct = (sim.ratio() * 100.0).round() as u16;
            let sent_fill = if sim.finished() {
                Color::Green
            } else {
                Color::Cyan
            };
            frame.render_widget(
                Gauge::default()
                    .gauge_style(Style::new().fg(sent_fill).bg(Color::Black).bold())
                    .ratio(sim.ratio())
                    .label(format!("sent  {sent}/{}  ({sent_pct}%)", sim.total)),
                rows[1],
            );

            let total = sim.checked.max(1);
            let done = sim.done();
            let prog = sim.in_progress();
            let pending = sim.pending();
            frame.render_widget(
                Paragraph::new(
                    format!(
                        "call status (API):  DONE {done}  ·  PROGRESS {prog}  ·  pending {pending}   / {}",
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
        lines.push(Line::from(
            "api unreachable — is elevator-api running on :8080?".red(),
        ));
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

fn draw_k8s(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" KUBERNETES · elevator (kind) ");
    let mut lines: Vec<Line> = Vec::new();

    let (mode_txt, mode_color) = match app.k8s.mode.as_str() {
        "fast" => ("FAST  (~instant moves)", Color::Cyan),
        "slow" => ("SLOW  (CPU-burning moves)", Color::Magenta),
        other => (other, Color::DarkGray),
    };
    lines.push(Line::from(vec![
        Span::from("mode  ").white(),
        Span::styled(
            format!(" {mode_txt} "),
            Style::new().fg(Color::Black).bg(mode_color).bold(),
        ),
    ]));
    lines.push(Line::from(""));

    if !app.k8s.reachable {
        lines.push(Line::from(
            format!("kubectl unavailable — {}", app.k8s.note).red(),
        ));
        frame.render_widget(
            Paragraph::new(lines)
                .block(block)
                .wrap(Wrap { trim: false }),
            area,
        );
        return;
    }

    lines.push(Line::from(
        format!(
            "  {:<34}{:<10}{:<8}{}",
            "POD", "STATUS", "READY", "RESTARTS"
        )
        .dark_gray(),
    ));
    for p in &app.k8s.pods {
        let st = match p.status.as_str() {
            "Running" => Style::new().fg(Color::Green),
            "Pending" | "ContainerCreating" => Style::new().fg(Color::Yellow),
            _ => Style::new().fg(Color::Red),
        };
        let ready_color = if p.ready == "true" {
            Color::Green
        } else {
            Color::Yellow
        };
        lines.push(Line::from(vec![
            Span::from(format!("  {:<34}", p.name)).white(),
            Span::styled(format!("{:<10}", p.status), st),
            Span::from(format!("{:<8}", p.ready)).fg(ready_color),
            Span::from(p.restarts.clone()).dark_gray(),
        ]));
    }

    frame.render_widget(Paragraph::new(lines).block(block), area);
}

fn draw_test(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" INTEGRATION TEST · console itest ");
    let mut lines: Vec<Line> = Vec::new();

    if app.test_running.load(std::sync::atomic::Ordering::Relaxed) {
        lines.push(Line::from(
            "⏳ running… sending orders, polling the api, cross-checking app+api logs".yellow(),
        ));
        lines.push(Line::from(""));
    }

    match &app.test_report {
        None => {
            lines.push(Line::from("no test run yet.".white()));
            lines.push(Line::from("press  r  to run the integration test.".dim()));
        }
        Some(r) => {
            let verdict = r["verdict"].as_str().unwrap_or("?");
            let vstyle = if verdict == "PASS" {
                Style::new().fg(Color::Black).bg(Color::Green).bold()
            } else {
                Style::new().fg(Color::Black).bg(Color::Red).bold()
            };
            lines.push(Line::from(vec![
                Span::from("verdict   ").white(),
                Span::styled(format!(" {verdict} "), vstyle),
                Span::from(format!(
                    "   run {}  ·  mode {}",
                    r["run_id"],
                    r["mode"].as_str().unwrap_or("?")
                ))
                .dark_gray(),
            ]));
            lines.push(Line::from(""));

            let lm = &r["latency_ms"];
            let kv = |k: &str, v: String| {
                Line::from(vec![
                    Span::from(format!("{k:<22}")).dark_gray(),
                    Span::from(v).green(),
                ])
            };
            lines.push(kv("requests", format!("{}", r["requests"])));
            lines.push(kv("done", format!("{}", r["done"])));
            lines.push(Line::from(vec![
                Span::from(format!("{:<22}", "lost")).dark_gray(),
                Span::styled(
                    format!("{}", r["lost"]),
                    if r["lost"].as_u64() == Some(0) {
                        Style::new().fg(Color::Green)
                    } else {
                        Style::new().fg(Color::Red).bold()
                    },
                ),
            ]));
            lines.push(kv(
                "latency p50/p95/max",
                format!("{} / {} / {} ms", lm["p50"], lm["p95"], lm["max"]),
            ));
            lines.push(kv(
                "throughput",
                format!(
                    "{:.2}/s",
                    r["throughput_done_per_s"].as_f64().unwrap_or(0.0)
                ),
            ));
            lines.push(kv(
                "api confirmed DONE",
                format!("{}", r["api_confirmed_done"]),
            ));
            lines.push(kv(
                "app moves observed",
                format!("{}", r["app_moves_observed"]),
            ));
            lines.push(Line::from(""));

            lines.push(Line::from("checks".dark_gray()));
            if let Some(checks) = r["checks"].as_array() {
                for c in checks {
                    let ok = c["ok"].as_bool().unwrap_or(false);
                    let st = if ok {
                        Style::new().fg(Color::Green)
                    } else {
                        Style::new().fg(Color::Red)
                    };
                    lines.push(Line::from(vec![
                        Span::styled(format!(" {} ", if ok { "PASS" } else { "FAIL" }), st.bold()),
                        Span::from(format!("  {}", c["check"].as_str().unwrap_or(""))).white(),
                    ]));
                }
            }
        }
    }

    frame.render_widget(
        Paragraph::new(lines)
            .block(block)
            .wrap(Wrap { trim: false }),
        area,
    );
}

fn draw_stats(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block(" SPARK BI · mileage + orders served ");

    let rows: Vec<&StatsRow> = app
        .stats
        .iter()
        .filter(|r| app.elevator_matches(&r.name))
        .collect();

    if rows.is_empty() {
        let msg = if app.stats.is_empty() {
            if app.health.reachable {
                Line::from("no stats yet — waiting for Spark BI (mileage / served)…".dim())
            } else {
                Line::from("⏳  no stats yet — elevator-api unreachable on :8080".yellow())
            }
        } else {
            Line::from(format!("no elevators match /{}/", app.elevator_filter).dim())
        };
        frame.render_widget(Paragraph::new(msg).block(block), area);
        return;
    }

    let max_mileage = rows.iter().map(|r| r.mileage).max().unwrap_or(0).max(1);
    let max_served = rows.iter().map(|r| r.served).max().unwrap_or(0).max(1);

    let inner_w = area.width.saturating_sub(2) as usize;
    // Layout per line:  name(6) mileage#(6) bar | served#(5) bar  — split the leftover between bars.
    let bar_w = inner_w.saturating_sub(6 + 6 + 3 + 5 + 1).max(4) / 2;

    let mut lines: Vec<Line> = Vec::new();
    lines.push(Line::from(vec![
        Span::from(format!("{:<6}", "CAR")).dark_gray(),
        Span::from(format!("{:>6} ", "MILE")).dark_gray(),
        Span::from(format!("{:<bar_w$}", "floors travelled")).dark_gray(),
        Span::from(" │ ").dark_gray(),
        Span::from(format!("{:>5} ", "SRVD")).dark_gray(),
        Span::from("orders served".to_string()).dark_gray(),
    ]));

    for r in &rows {
        lines.push(Line::from(vec![
            Span::from(format!("{:<6}", r.name)).cyan(),
            Span::from(format!("{:>6} ", r.mileage)).white(),
            Span::styled(
                bar(r.mileage, max_mileage, bar_w),
                Style::new().fg(Color::Green),
            ),
            Span::from(" │ ").dark_gray(),
            Span::from(format!("{:>5} ", r.served)).white(),
            Span::styled(
                bar(r.served, max_served, bar_w),
                Style::new().fg(Color::Cyan),
            ),
        ]));
    }

    frame.render_widget(Paragraph::new(lines).block(block), area);
}

/// A proportional bar `width` cells wide, filled to `value/max`. Pure — unit-tested.
fn bar(value: i64, max: i64, width: usize) -> String {
    if max <= 0 || value <= 0 {
        return String::new();
    }
    let filled = ((value as f64 / max as f64) * width as f64).round() as usize;
    "█".repeat(filled.min(width))
}

fn draw_footer(frame: &mut Frame, app: &App, area: Rect) {
    let block = retro_block("");
    let content = match app.view {
        View::Chart | View::Trend | View::Stats => {
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
        View::Call => {
            let mut spans = vec![
                Span::from("call ▸ ").green().bold(),
                Span::from(app.call_input.clone()).white(),
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
                Span::from("   number of calls · Enter: run").dark_gray(),
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
        View::Health => Line::from("Tab/Shift-Tab: switch view   ·   Esc: quit".dim()),
        View::K8s => {
            let mut spans = vec![
                Span::from("change configmap ▸ ").green().bold(),
                Span::from("f").fg(Color::Black).bg(Color::Cyan).bold(),
                Span::from(": fast  ").dark_gray(),
                Span::from("s").fg(Color::Black).bg(Color::Magenta).bold(),
                Span::from(": slow   ").dark_gray(),
                Span::from("r").fg(Color::Black).bg(Color::Yellow).bold(),
                Span::from(": restart").dark_gray(),
                Span::from("   (configmap swap / rollout — via kubectl)").dark_gray(),
            ];
            if !app.message.is_empty() {
                spans.push(Span::from(format!("   {}", app.message)).cyan());
            }
            Line::from(spans)
        }
        View::Test => {
            let mut spans = vec![
                Span::from("integration test ▸ ").green().bold(),
                Span::from("r").fg(Color::Black).bg(Color::Green).bold(),
                Span::from(": run").dark_gray(),
                Span::from("   (sends calls, polls api, cross-checks app+api logs)").dark_gray(),
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
            out.push(c);
            while chars.peek().is_some_and(|n| n.is_alphabetic()) {
                chars.next();
            }
        } else {
            out.push(c);
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
    use super::bar;

    #[test]
    fn bar_scales_value_against_max() {
        assert_eq!(bar(10, 10, 20).chars().count(), 20);
        assert_eq!(bar(5, 10, 20).chars().count(), 10);
        assert_eq!(bar(0, 10, 20), "");
    }

    #[test]
    fn bar_is_empty_and_safe_for_nonpositive_max() {
        assert_eq!(bar(5, 0, 20), "");
        assert_eq!(bar(-3, 10, 20), "");
    }

    #[test]
    fn bar_never_exceeds_width() {
        // value > max shouldn't overflow the bar width.
        assert_eq!(bar(30, 10, 8).chars().count(), 8);
    }
}
