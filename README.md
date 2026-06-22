# Elevator System

An event-sourced elevator simulator, built as a hands-on lab for **modern distributed
programming** on (and off) the JVM:

- **Scala 3** — the pure domain (elevator, floors, scheduling policy)
- **Apache Pekko** — typed actors, cluster sharding, event sourcing + projections (the runtime)
- **PostgreSQL** (reactive **R2DBC**) — durable event journal + a CQRS read-model projection
- **Apache Kafka** — the command/state bus (log-centric architecture)
- **Spring Boot** (+ Actuator) — the HTTP edge and health probes
- **Rust** (ratatui) — a retro terminal console that speaks the same Kafka topics

It's grown in small, deliberate commits — read the history to follow the architecture
coming together.

## Architecture

```
                  ┌─────────────── elevator-api (Spring) ───────────────┐
  POST /api/order │                                                      │ ─► Kafka: elevator-commands
  GET  /api/...   │   REST edge  +  /actuator/health (liveness/readiness)│        │
                  └──────────────────────▲──────────────────────────────┘        ▼
                                         │                            Coordinator (idempotent dedup)
                       Kafka: elevator-state                                       │
                                         │                            Controller (event-sourced scheduler)
   ┌──────────────── elevator-console (Rust) ───────────────┐                     │ one move / tick
   │  TUI: chart · floor-over-time · health · logs           │                    ▼
   │  send orders · bulk "sim"                               │ ◄─ Kafka: state ◄─ Operator (moves the car)
   └─────────────────────────────────────────────────────────┘
```

Both the Spring API and the Rust console are independent clients of the same two Kafka
topics — the console talks straight to Kafka, the API adds HTTP on top.

### Persistence & read side (CQRS)

The `Controller` is event-sourced into a durable **R2DBC Postgres journal** (state survives
restarts, rebuilt by replaying events). A separate **Pekko Projection** reads those events back
out by slice and maintains a queryable read-model — the write side and read side are different
concerns, different tables:

```
  Controller ──persist──► event_journal (Postgres, source of truth)
                                  │  eventsBySlices  (read-only)
                                  ▼
                      ElevatorStateProjection  ──UPSERT──►  elevator_state_view
                      (ShardedDaemonProcess,                (one row per elevator:
                       role "read-model",                    floor/direction/motion,
                       exactly-once offsets)                 queryable with SQL)
```

Kafka (the `elevator-state` topic) stays as the **live, ephemeral** broadcast for the API cache
and console; the projection is the **durable, queryable** view derived from the journal.

### Live vs durable: which source to read?

Two read paths, two jobs — pick by what the consumer needs:

| Consumer need | Read from | Why |
|---|---|---|
| **Online/real-time monitor** (ticking dashboard, console) | **Kafka** `elevator-state` | push-based, sub-second; ephemeral is fine for "now" |
| **Durable query / snapshot / history** (REST, survives restart) | **projection** `elevator_state_view` | complete & correct even right after a restart; queryable with SQL |

Best of both for a live UI: **seed** the initial picture once from `elevator_state_view` (so nothing
is blank at startup), then **stream** live updates from the Kafka topic.

| Module                 | Stack   | Role                                                                 |
|------------------------|---------|---------------------------------------------------------------------|
| `elevator-common-core` | Scala 3 | Pure domain: elevator, floors, scheduling `Policy`                   |
| `elevator-common-dto`  | Scala 3 | Messages shared across the wire                                     |
| `elevator-app`         | Pekko   | The brain: event-sourced `Coordinator` / `Controller` / `Operator` + R2DBC journal & read-side projection |
| `elevator-api`         | Spring  | HTTP edge + Actuator health (Kafka readiness check)                 |
| `elevator-console`     | Rust    | Terminal UI: live chart, floor-over-time, actuator health, log viewer; order + bulk `sim` |

## Run

```bash
scripts/demo-up.sh            # Kafka + Postgres (docker) + elevator-app + elevator-api (host JVMs)
# then, the rich console:
cd elevator-console && cargo run -- monitor      # Tab: chart / trend / health / logs
scripts/demo-down.sh          # stop everything

# inspect the durable read-model:
docker exec -i elevator-demo-postgres psql -U elevator -d elevator -c \
  "SELECT * FROM elevator_state_view;"
```

See **[demo.md](demo.md)** for the scripted demo and endpoints, and
**[elevator-console/README.md](elevator-console/README.md)** for the console.

## Build

Maven multi-module, Java 21. `mvn package` builds the JVM modules (a `maven-enforcer`
rule guards dependency convergence). The Rust console is a separate `cargo` build,
wired in behind `-Pconsole` / `-Dcargo.skip=false` — see the console README.

## Why this project exists

A sandbox for the patterns behind resilient distributed systems — the actor model,
event sourcing / CQRS, log-centric messaging, idempotency, backpressure, and
observability — small enough to read in an afternoon, real enough to break on purpose
and learn from.

## Roadmap

**Done:** durable **R2DBC Postgres journal** (state survives restarts) + a **Pekko Projection**
maintaining the `elevator_state_view` read-model, with recovery & schema-evolution tests
(`mvn -pl elevator-app test`).

Next: point the API's read path at the projection (a reactive `GET /elevators` over
`elevator_state_view`) instead of the in-memory Kafka cache, so HTTP queries are durable and
restart-safe. Further out: a multi-node cluster (the projection is already role-gated to
`read-model` nodes), a separate read database, CRDTs (`distributed-data`), and
chaos/fault-injection drills.
