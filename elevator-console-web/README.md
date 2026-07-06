# elevator-console-web (Angular)

A **read-only** browser monitor for the elevator system, mirroring the Rust `elevator-console-cli`'s
two view tabs — **Chart** and **Trend**. It talks to the system **only through the `elevator-api`
HTTP edge** and never touches Kafka directly.

Angular 21 (standalone, zoneless, signals). Charts are rendered with **Apache ECharts**
(themed to a Material palette, light/dark aware); the option builders (`chart-options.ts`)
are pure functions and unit-tested.

## What it does

| Concern    | Endpoint                        |
|------------|---------------------------------|
| live state | SSE `GET /api/elevator/stream`  |
| health     | `GET /actuator/health`          |

Two tabs, sharing one **regex name filter** (like the console):

- **Chart** — live floor view: each elevator is a cab (rounded rect) parked at its floor on a
  shared floor axis; the cab **glides** to its new floor on each SSE tick. Colour carries state
  (accent = moving, muted = idle); a chevron shows direction, and hover gives a per-cab tooltip.
- **Trend** — floor-over-time: one smooth line per elevator (last 48 samples) on a shared floor
  axis, with an end-label per line and a shared-axis tooltip.

Tests run on **vitest** (`npm test`): the filter, the SSE ingestion, and the chart options.

Fed by SSE; the browser's `EventSource` auto-reconnects across backend restarts. Header badges
show the SSE connection and api health. The floor range mirrors `elevator-api` `application.yml`
(`max-floor: 15`).

## Dev

Needs Node + npm on PATH.

```bash
npm install
npm start          # ng serve on http://localhost:4200
```

`npm start` uses `proxy.conf.json` to forward `/api` and `/actuator` to
`http://localhost:8080`, so run `elevator-api` locally first. No CORS setup needed
(the api already allows all origins on `/api/**`), the proxy just keeps everything same-origin.

```bash
npm run build      # production bundle → dist/elevator-console-web
```

## Build with Maven

The Angular build is part of a normal `mvn` build — no profile, no global Node needed.
`frontend-maven-plugin` downloads a project-local Node/npm into `target/` and runs
`npm ci` + `npm run build`:

```bash
mvn package                 # builds backend + this frontend (dist/elevator-console-web)
mvn -Dnpm.skip=true package # quick backend-only build, skip the frontend
```

First build downloads Node (~30 MB) into `target/node` (cached; removed by `mvn clean`).
