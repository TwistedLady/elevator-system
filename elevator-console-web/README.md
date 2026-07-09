# elevator-console-web (Elm)

A **read-only** browser monitor for the elevator system, mirroring the Rust `elevator-console-cli`'s
view tabs. It talks to the system **only through the `elevator-api` HTTP edge** and never touches
Kafka directly.

Written in **Elm** (The Elm Architecture: `Model` / `update` / `view`), built with **Vite** +
`vite-plugin-elm`. Charts are rendered with **Apache ECharts** on a canvas — wrapped in an
`<echarts-panel>` custom element so Elm owns the *option* (built in `Chart.elm`) while the element
owns the pixels, resize, and the JS-only tooltip formatter.

## What it does

| Concern    | Endpoint                               |
|------------|----------------------------------------|
| live state | SSE `GET /api/elevator/stream`         |
| config     | `GET /api/config` (maxFloor, biEnabled)|
| health     | `GET /actuator/health`                 |
| version    | `GET /api/version`                     |
| BI stats   | `GET /api/mileage`, `GET /api/served`  |

Three tabs, sharing one **regex name filter** (like the console):

- **Chart** — live floor view: each elevator is a cab (rounded rect) parked at its floor on a
  shared floor axis; the cab **glides** to its new floor on each SSE tick. Colour carries state
  (accent = moving, hollow = idle); a chevron shows direction, and hover gives a per-cab tooltip.
- **Trend** — floor-over-time: one smooth line per elevator (last 48 samples) on a shared floor
  axis, with an end-label per line and a shared-axis tooltip.
- **Stats** — Spark BI outcomes per elevator (mileage + orders served), polled and drawn as small
  horizontal bars. Hidden when `biEnabled` is false.

Header badges show the SSE connection, api health, and this build's version vs. the backend's.
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
| `src/Main.elm`   | wiring: `Model`, `Msg`, `update`, `subscriptions`, `view` |
| `src/Types.elm`  | domain types + JSON decoders (custom types: `Direction`, `Motion`, `Stream`, `Health`, `BackendVersion`, `Tab`, `Theme`) |
| `src/Api.elm`    | REST calls + resolvers |
| `src/Chart.elm`  | ECharts option encoders (position + trend) |
| `src/Stats.elm`  | Stats tab: merge + view |
| `src/Filter.elm` | regex/substring name filter |
| `src/Ports.elm`  | SSE + theme ports |
| `src/main.js`    | JS glue: `<echarts-panel>`, SSE, theme, Elm init |

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
