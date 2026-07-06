# Elevator System — docs

> Event-sourced elevator simulator. Scala 3 domain · Pekko actors + R2DBC journal ·
> Kafka bus · Spring HTTP edge · Rust TUI console.

One topic per file. New here? Read [architecture](architecture.md) first.

## Explanation — understand it
- [architecture.md](architecture.md) — modules and data flow, one map.
- [read-model.md](read-model.md) — CQRS: live Kafka feed vs durable projections.
- [crash-recovery.md](crash-recovery.md) — what replay guards, and why.

## Reference — look it up
- [actors.md](actors.md) — the three actors and who does what.
- [protocol.md](protocol.md) — exact messages, events, topics, end-to-end sequence.
- [scheduling.md](scheduling.md) — the SCAN next-move policy.
- [core.md](core.md) — `elevator-common-core`: domain + engine.
- [cicd.md](cicd.md) — CI build/test gate and CD deploy.
- [versioning.md](versioning.md) — one `VERSION` file; how the API and both consoles check it.

## How-to — do it
- [run.md](run.md) — run the demo, endpoints, live view, config.
- [apt-repo.md](apt-repo.md) — install the console with `apt install` from a signed local repo.
- [dev-worktrees.md](dev-worktrees.md) — many branches in one IntelliJ window.

---
_Each file is the single source for its topic; others link here, never re-explain.
Diagrams are [Mermaid](https://mermaid.js.org). Trust the code if a doc drifts._
