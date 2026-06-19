# Elevator System

An event-sourced elevator simulator, built as a hands-on lab for **modern distributed
programming** on (and off) the JVM:

- **Scala 3** — the pure domain (elevator, floors, scheduling policy)
- **Apache Pekko** — typed actors, cluster sharding, event sourcing (the runtime)
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

| Module                 | Stack   | Role                                                                 |
|------------------------|---------|---------------------------------------------------------------------|
| `elevator-common-core` | Scala 3 | Pure domain: elevator, floors, scheduling `Policy`                   |
| `elevator-common-dto`  | Scala 3 | Messages shared across the wire                                     |
| `elevator-app`         | Pekko   | The brain: event-sourced `Coordinator` / `Controller` / `Operator`  |
| `elevator-api`         | Spring  | HTTP edge + Actuator health (Kafka readiness check)                 |
| `elevator-console`     | Rust    | Terminal UI: live chart, floor-over-time, actuator health, log viewer; order + bulk `sim` |

## Run

```bash
scripts/demo-up.sh            # Kafka (docker) + elevator-app + elevator-api (host JVMs)
# then, the rich console:
cd elevator-console && cargo run -- monitor      # Tab: chart / trend / health / logs
scripts/demo-down.sh          # stop everything
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

Next step is real **CQRS read-models**: a **Postgres-backed Pekko Projection** that consumes
the event journal (the `Controller` already tags its `ElevatorStateUpdated` events) and
maintains durable query tables — replacing the API's in-memory `StateStore` so state
survives restarts and can be queried with SQL. Further out: a multi-node cluster, a durable
journal, CRDTs (`distributed-data`), and chaos/fault-injection drills.
