# elevator-console-web (Elm)

A browser console for the elevator system, sharing its shape (one header + three tabs) with the Rust
`elevator-console-cli`. Mostly a monitor, but the **Sim** tab can also trigger a run. It talks to the
system **only through the `elevator-api` HTTP edge** and never touches Kafka directly.

Written in **Elm** (The Elm Architecture: `Model` / `update` / `view`), built with **Vite** +
`vite-plugin-elm`. Charts are rendered with **Apache ECharts** on a canvas — wrapped in an
`<echarts-panel>` custom element so Elm owns the *option* (built in `Chart.elm`) while the element
owns the pixels, resize, and the JS-only tooltip formatter.

## What it does

| Concern | Endpoint |
|---|---|
| live state | SSE `GET /api/elevator/stream` |
| config | `GET /api/config` (maxFloor, elevators) |
| health | `GET /actuator/health` |
| version | `GET /api/version` |
| start a simulation | `POST /api/simulate` → `{runId, count, ids}` |
| simulation progress | `GET /api/simulate/progress?runId=..&size=..` |

One shared header — title `🛗 ELEVATOR CONSOLE`, an **API health** badge and a **version** badge
(web vs. backend) — over three tabs. Chart and Trend share one **regex name filter** (like the
console):

- **Chart** — live floor view: each elevator is a cab (rounded rect) parked at its floor on a
  shared floor axis; the cab **glides** to its new floor on each SSE tick. Colour carries state
  (accent = moving, hollow = idle); a chevron shows direction, and hover gives a per-cab tooltip.
- **Trend** — floor-over-time: one smooth line per elevator (last 48 samples) on a shared floor
  axis, with an end-label per line and a shared-axis tooltip.
- **Sim** — one **`Run 10k simulation`** button fires a server-side run (`POST /api/simulate`, no
  body → the api's default 10,000 random calls), then polls `GET /api/simulate/progress` by run id
  every ~2s and draws a **progress bar** split by status (green DONE / yellow in-progress / gray
  pending, from size/calls/doneCalls) plus a summary (size · calls · orders · done · first/last
  times) and `✓ simulation complete` when done ≥ size.

Header badges show api health and this build's version vs. the backend's.
The browser's `EventSource` auto-reconnects across backend restarts. The floor range is read live
from `/api/config` (never hardcoded).

### Interop, on purpose

- **Custom element** (`<echarts-panel>`) for the chart — the modern Elm way to wrap a JS widget,
  with no port/DOM-timing races.
- **Ports** only for genuine event sources: the SSE stream (`Ports.elm` + `main.js`) and the OS
  colour-scheme change.

## Source map

| File | Role |
|---|---|
| `src/Main.elm`    | orchestrator: top-level `Model`/`Msg`, tab switching, subscriptions, `view` — delegates each tab |
| `src/Types.elm`   | domain types + JSON decoders (`Direction`, `Motion`, `Health`, `BackendVersion`, `Tab`, `Theme`, `SimulateResult`, `SimProgress`) |
| `src/Api.elm`     | REST calls: config, health, version, simulate, progress |
| `src/Header.elm`  | the shared header: title + health badge + version badge (+ version-mismatch warnbar) |
| `src/Chart.elm`   | CHART tab + the shared palette / floor axis; ECharts option encoders |
| `src/Trend.elm`   | TREND tab: floor-over-time lines (reuses Chart's palette/axis) |
| `src/Sim.elm`     | SIM tab: run button → poll progress → status-split bar + summary |
| `src/Filter.elm`  | regex/substring name filter (shared by Chart + Trend) |
| `src/History.elm` | rolling per-elevator floor history for Trend |
| `src/Ports.elm`   | SSE + theme ports |
| `src/Log.elm`     | structured browser-console logging via a port |
| `src/main.js`     | JS glue: `<echarts-panel>`, SSE, theme, Elm init |

## Dev

Needs Node + npm on PATH.

```bash
npm install
npm start          # vite dev server on http://localhost:5173 (HMR)
```

`npm start` proxies `/api` and `/actuator` to `http://localhost:8080` (see `vite.config.js`), so
run `elevator-api` locally first — everything stays same-origin.

```bash
npm run build      # production bundle → dist/elevator-console-web/browser
```

## Build with Maven

The Elm build is part of a normal `mvn` build — no profile, no global Node needed.
`frontend-maven-plugin` downloads a project-local Node/npm into `target/` and runs `npm ci` +
`npm run build` (which invokes the Elm compiler via `vite-plugin-elm`):

```bash
mvn package                 # builds backend + this frontend (dist/elevator-console-web/browser)
mvn -Dnpm.skip=true package # quick backend-only build, skip the frontend
```

First build downloads Node (~30 MB) into `target/node` (cached; removed by `mvn clean`); the Elm
compiler binary is fetched by `npm ci` (the `elm` devDependency's install script).
