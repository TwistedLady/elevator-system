# CLAUDE.md — Elevator System

Guidance for AI agents (and humans) working in this repo. Keep it short and true. If this file and
the code disagree, **trust the code** and fix this file.

What the system *is* — modules, data flow, endpoints, actors, architecture — lives in
**[README.md](README.md)**. This file holds only the working rules README doesn't cover.

## Hard rules (do not break without an explicit ask)

- **Never edit `pom.xml`** (artifactId / name / modules) to fix IDE or naming issues — fix those on
  the IntelliJ side. Module id stays `pl.feelcodes.elevator:elevator`. **Never hand-edit version
  numbers** — the version is `${revision}` (root `pom.xml`), mirrored to `VERSION` + all modules and
  bumped automatically by release-please ([README.md](README.md#versioning)).
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

Every session is an isolated **developer**: **one session = one branch = one dir**.

- `main` is the trunk — **never developed on directly.** Each task = its own **git worktree +
  branch**, a sibling of `elevator-system/`: `git worktree add ../elevator-<task> -b <task> main`.
  **One topic per session.**
- A session works **only in its own dir** — it reads only the shared `main`, never another session's
  dir. Commit **freely and in small commits**.
- **Definition of done:** push → open PR → merge → **delete the branch** → **update the docs**. The
  dir ends clean, back on `main`.
- **Kanban** (`../kanban.md`), three parts: **Current** (one table per active session, newest on top,
  cells 🟩/🟨/⬜), **To-do** (bugs, ideas), **Changelog** (one entry per commit, newest first).
- **Knowledge split:** domain/project facts live in **[README.md](README.md)** (update after each
  PR). The base-dir `../.knowledge/` is a symlink to the agent's own memory (across sessions).
- **IntelliJ / Maven module naming:** the user marks the Maven project himself in one IntelliJ
  window — do not automate renaming, and never edit `pom.xml`.

## Known gotchas / open issues

- **Engine is a real-time cost, isolated.** `Engine.burn()` is `Thread.sleep(cost)` — the move's
  travel time (`SlowEngine` 2s, `FastEngine` 100ms), pacing the Controller's self-driven loop. It
  blocks, so `Operator` entities run on the dedicated `elevator-blocking-dispatcher`
  (application.conf) — never the default one. Keep that isolation.
- **API Kafka state consumer replays the whole topic on restart (by design).** `ElevatorStateConsumer`
  uses `auto.offset.reset=earliest` with no offset commit (`enable.auto.commit=false`), so every
  start rebuilds the full per-elevator view — a fanout, so each api replica sees all elevators. The
  group id is per-pod (`elevator-api-monitor-<POD_NAME>`) so replicas don't split partitions and
  empty groups don't leak.
- **Huge sims bloat the journal** → slow recovery → "Kafka stream failed", app stops consuming. Wipe
  + reseed to recover (`docker compose … down -v`, then `--profile seed`). Both the demo compose and
  the kind chart put Kafka + Postgres on volumes, so a plain restart keeps the feed and the journal.
- **`DefaultScalaModule` is registered in the pure-Java api** and a custom `ObjectMapper` bean
  overrides Boot's auto-config. Leftover — safe to remove.
- **Rust console has unit tests** (`cargo test`, run by CI). Keep it that way: add a test alongside
  new pure Rust functions.
- **Auth signing key is in-process** — multiple `api` replicas reject each other's JWTs; mount a
  fixed key as a Secret before scaling past one replica ([README.md](README.md#auth)).
- **Docs drift.** Re-verify docs after a refactor — code is the source of truth. The docs are one
  file: [`README.md`](README.md).
