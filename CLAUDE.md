# CLAUDE.md — Elevator System

Guidance for AI agents (and humans) working in this repo. Keep it short and true.
If this file and the code disagree, **trust the code** and fix this file.

## What this is

An event-sourced elevator simulator, built as a lab for distributed programming on the JVM.
Scala 3 domain + Apache Pekko (cluster sharding, event sourcing, projections), a Spring WebFlux
HTTP edge, Postgres (R2DBC journal + CQRS read model), Kafka as the command/state bus, and a
Rust (ratatui) terminal console.

## Modules

| Module | Lang | Role |
|---|---|---|
| `elevator-common` | Scala | Shared library, split into small submodules (below) |
| `elevator-app` | Scala / Pekko | The brain: sharded, event-sourced actors + Postgres projections |
| `elevator-api` | Java / WebFlux | HTTP edge: REST + SSE, Kafka producer/consumer, R2DBC reads, health |
| `elevator-console-cli` | Rust / ratatui | Terminal dashboard + order sender |
| `elevator-console-web` | Angular | Read-only browser monitor (Chart + Trend tabs), talks to the api only |
| `elevator-bi` | Scala 2.12 / Spark | **Standalone** (not in the reactor): Spark BI jobs → Postgres — streaming **mileage** from `elevator-state`, batch **orders-served** (DONE counts) from `order_status`. Build: `mvn -f elevator-bi/pom.xml package` |

`elevator-common` submodules keep a clean layering:
`core` (pure domain) → `events` → `logic` (decide/evolve, Pekko-free) → `protocol` (message ADTs, Pekko-free)
→ `strategy`, `dto`, `serializable`. The app actors are **thin shells** that wire the pure `logic`.

## How it flows (one order)

`POST /api/order` → api produces to Kafka `elevator-commands` → app `OrderConsumer` dedups by `tag`
→ `Coordinator` (merge orders by floor, persist Accepted) → `Controller` (event-sourced scheduler;
self-driven loop, no timer — pacing comes from the engine; picks next move via `NextFloorStrategy`)
→ `Operator` (stateless, applies one move)
→ Controller publishes new state to Kafka `elevator-state` and marks reached orders done.

Two Kafka topics: `elevator-commands` (api → app), `elevator-state` (app → api / console).

> **The Rust console is HTTP-only.** It reaches the system **only** via the elevator-api HTTP edge
> (`POST /api/order`, `GET /api/elevator`, `GET /api/elevator/stream` SSE) plus infra (`kubectl`/`git`).
> It does **not** talk to Kafka. (The README's top diagram is out of date on this — trust this note.)

## Hard rules (do not break without an explicit ask)

- **Never edit `pom.xml`** (artifactId / name / version / modules) to fix IDE or naming issues.
  Fix those on the IntelliJ side. Module id must stay `pl.feelcodes.elevator:elevator`.
- **`core` and `protocol` classes are frozen.** They carry a top `// Reviewed —` header — keep it
  forever, and don't edit these classes without explicit permission.
- **Don't add code comments without asking.** Approved comments stay short and meaningful; strip
  comments when refactoring.
- **Run the test suite after every code change**, before reporting done. Don't wait to be asked.
- **Diagrams:** always Mermaid. **Progress bars:** split by status (DONE / IN-PROGRESS / pending),
  not one overall %.
- Dependency bumps (Pekko / Kafka / Scala): `scala` version = Pekko's stdlib; `kafka` client
  ≥ connector and = broker image; enforcer requires upper-bound deps.

## Build & test

```bash
./mvnw test                 # JVM modules (Scala + Java unit tests)
./mvnw -Pconsole install    # also build the Rust console (cargo is opt-in; skipped by default)
```

- Failsafe `*IT` tests (Testcontainers) need `classesDirectory=classes` and the `api.version` system
  prop set, or you get cryptic Spring/Docker errors.
- `pekko-persistence-r2dbc` needs an explicit `org.postgresql:r2dbc-postgresql` dependency — missing
  it fails only at runtime (`NoClassDefFoundError`).
- Renaming an actor message trait means also editing the string FQNs in `application.conf`
  (`serialization-bindings`) — only runtime/demo catches a mismatch.

## Workflow

- `main` is the trunk — **never developed on directly.**
- Every task = its own **git worktree + branch**, a sibling of `elevator-system/`:
  `git worktree add ../elevator-<task> -b <task> main`. Commit freely on task branches.
- Base-dir `../kanban.md` tracks the branches (each branch = a "dev"); update it once per cycle.
- Durable workspace knowledge and IntelliJ module-rename skills live in the base dir
  (`../.knowledge/`, `../.skill/`) — outside the git repo, shared across worktrees.

## Known gotchas / open issues

Real hazards to be aware of (fix deliberately, on their own branch):

- **Engine is a real-time cost, isolated.** `Engine.burn()` is `Thread.sleep(cost)` — `cost` is the
  move's travel time (`SlowEngine` 2s, `FastEngine` 100ms), modelling the physical action and pacing
  the Controller's self-driven loop. It blocks, so `Operator` entities run on the dedicated
  `elevator-blocking-dispatcher` (application.conf) — never the default one. Keep that isolation.
- **API Kafka state consumer replays the whole topic on restart (by design).** `ElevatorStateConsumer`
  uses `auto.offset.reset=earliest` with **no offset commit** (`enable.auto.commit=false`), so every
  start rebuilds the full per-elevator view — needed because it's a fanout (each api replica must see
  all elevators). The group id is per-pod (`elevator-api-monitor-<POD_NAME>`) so replicas don't split
  partitions and, with no committed offsets, empty groups don't leak.
- **Huge sims bloat the journal** → slow recovery → "Kafka stream failed", app stops consuming.
  Wipe + reseed to recover (Kafka has no volume, so a demo restart also empties the live chart —
  reseed via `demo-up.sh`).
- **`DefaultScalaModule` is registered in the pure-Java api** and a custom `ObjectMapper` bean
  overrides Boot's auto-config. Leftover — safe to remove.
- **Rust console has unit tests now** (`cargo test` in `elevator-console-cli`, run by CI). Keep it that
  way: when adding pure Rust functions, add a test alongside them.
- **Docs drift.** Re-verify docs/comments after a refactor — code is the source of truth. Full
  docs are in [`docs/`](docs/README.md) (one topic per file, indexed).
