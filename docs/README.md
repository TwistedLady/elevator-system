# Elevator System — docs

> Event-sourced elevator simulator. Scala 3 domain · Pekko actors + R2DBC journal ·
> Kafka bus · Spring HTTP edge · Rust TUI console.

One topic per file. New here? Read [architecture](architecture.md) first.

## Explanation — understand it
- [architecture.md](architecture.md) — modules and data flow, one map.
- [read-model.md](read-model.md) — CQRS: live Kafka feed vs durable projections.
- [crash-recovery.md](crash-recovery.md) — what replay guards, and why.

## Reference — look it up
- [dictionary.md](dictionary.md) — the words (Call vs Order, the four actors, tables, topics).
- [actors.md](actors.md) — the four actors (Call vs Order) and who does what.
- [actor-contract.md](actor-contract.md) — each actor's state, commands, events, publishes (the schema).
- [protocol.md](protocol.md) — exact messages, events, topics, end-to-end sequence.
- [auth.md](auth.md) — optional HTTP Basic on `POST /api/call`; the username is the passenger.
- [scheduling.md](scheduling.md) — the SCAN next-move policy.
- [suspender.md](suspender.md) — the move gate: the Controller asks the SuspendManager first.
- [core.md](core.md) — `elevator-common-core`: domain + engine.
- [cicd.md](cicd.md) — CI build/test gate and CD deploy.
- [versioning.md](versioning.md) — one `VERSION` file; how the API and both consoles check it.

## How-to — do it
- [run.md](run.md) — run the demo, endpoints, live view, config.
- [cluster.md](cluster.md) — run on kind with Terraform · Helm · Skaffold (no shell scripts).
- [apt-repo.md](apt-repo.md) — install the console with `apt install` from a signed local repo.
- [dev-worktrees.md](dev-worktrees.md) — many branches in one IntelliJ window.

---
_Each file is the single source for its topic; others link here, never re-explain.
Diagrams are [Mermaid](https://mermaid.js.org). Trust the code if a doc drifts._
