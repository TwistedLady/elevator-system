# CLAUDE.md — Elevator System

Guidance for AI agents (and humans) working in this repo. Keep it short and true.
If this file and the code disagree, **trust the code** and fix this file.

## What this is

An event-sourced elevator simulator, built as a lab for distributed programming on the JVM.
Scala 3 domain + Apache Pekko (cluster sharding, event sourcing, projections), a Spring WebFlux
HTTP edge, Postgres (R2DBC journal + CQRS read model), Kafka as the call / state bus, and a
Rust (ratatui) terminal console.

## Modules

| Module | Lang | Role |
|---|---|---|
| `elevator-common` | Scala | Shared library, split into small submodules (below) |
| `elevator-app` | Scala / Pekko | The brain: sharded, event-sourced actors + Postgres projections |
| `elevator-api` | Java / WebFlux | HTTP edge: REST + SSE, Kafka producer/consumer, R2DBC reads, health |
| `elevator-console-cli` | Rust / ratatui | Terminal dashboard + call sender |
| `elevator-console-web` | Angular | Read-only browser monitor (Chart + Trend tabs), talks to the api only |
| `elevator-bi` | Scala 2.12 / Spark | **Standalone** (not in the reactor): Spark BI jobs → Postgres — streaming **mileage** from `elevator-state`, batch **orders-served** (DONE counts) from `order_status`. Build: `mvn -f elevator-bi/pom.xml package` |

`elevator-common` submodules keep a clean layering:
`core` (pure domain) → `events` → `logic` (decide/evolve, Pekko-free) → `protocol` (message ADTs, Pekko-free)
→ `strategy` (`NextFloorStrategy` movement, `GroupCallsStrategy` grouping) → `dto`, `serializable`.
The app actors are **thin shells** that wire the pure `logic`.

## How it flows (one call)

A **Call** = a user action (`id, elevatorName, floor`, optional `passengerId`). The app groups
same-floor calls into one living **Order** (`order id = f(elevator, floor)`; later same-floor calls
attach until it is done) — one stop. The Order counts distinct riders vs. anonymous presses. Four
actors, one per elevator:

`POST /api/call` → api produces to Kafka `elevator-calls` → app `CallConsumer` (batches, dedups by
call `id`) → `Coordinator` (owns call status: persist `CallReceived`, forward) → `Manager` (owns
call↔order: group via `GroupCallsStrategy`, persist `OrderCreated`, assign) → `Controller`
(event-sourced scheduler; self-driven loop, no timer — engine paces it; next move via
`NextFloorStrategy`) → `Operator` (stateless, applies one move) → Controller publishes state to
Kafka `elevator-state` and marks reached orders done → `Manager.MarkDone` → `Coordinator.MarkDone`.

Four Kafka topics: `elevator-calls` (api → app), and three state feeds (app → api/console/BI):
`elevator-state`, `elevator-order-state`, `elevator-call-state`. Status query: `GET /api/call/{id}`
reads the `call_status` read table. Full detail: [docs/protocol.md](docs/protocol.md).

> **The Rust console is HTTP-only.** It reaches the system **only** via the elevator-api HTTP edge
> (`POST /api/call`, `GET /api/elevator`, `GET /api/elevator/stream` SSE) plus infra (`kubectl`/`git`).
> It does **not** talk to Kafka.

## Hard rules (do not break without an explicit ask)

- **Never edit `pom.xml`** (artifactId / name / version / modules) to fix IDE or naming issues.
  Fix those on the IntelliJ side. Module id must stay `pl.feelcodes.elevator:elevator`.
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

Think of every session as an isolated **developer**: **one session = one branch = one dir**.

- `main` is the trunk — **never developed on directly.**
- Each task = its own **git worktree + branch**, a sibling of `elevator-system/`:
  `git worktree add ../elevator-<task> -b <task> main`. **One topic per session**; if it would grow
  past two topics, split into separate sessions.
- A session works **only in its own dir** — it never reads another session's dir, only the shared
  `main`. Commit **freely and in small commits**.
- **Definition of done (every task):** push → open PR → merge → **delete the branch** → **update the
  docs**. Nothing is left behind: the dir ends clean, back on `main`.
- **Kanban** (`../kanban.md`) has three parts: **Current** (one table per active session, newest on
  top — caption = task, columns = subtasks, cells = 🟩/🟨/⬜), **To-do** (bugs, ideas), and
  **Changelog** (one entry per commit, entry # = commit #, plain words, newest first).
- **Knowledge split:** domain/project facts live in `docs/` (update after each PR). The base-dir
  `../.knowledge/` is a symlink to the agent's own memory (what it knows across sessions).
- **IntelliJ / Maven module naming:** the user marks the Maven project himself in one IntelliJ
  window — do not automate renaming, and never edit `pom.xml`.

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
  reseed via the compose `--profile seed`).
- **`DefaultScalaModule` is registered in the pure-Java api** and a custom `ObjectMapper` bean
  overrides Boot's auto-config. Leftover — safe to remove.
- **Rust console has unit tests now** (`cargo test` in `elevator-console-cli`, run by CI). Keep it that
  way: when adding pure Rust functions, add a test alongside them.
- **Docs drift.** Re-verify docs/comments after a refactor — code is the source of truth. Full
  docs are in [`docs/`](docs/README.md) (one topic per file, indexed).
