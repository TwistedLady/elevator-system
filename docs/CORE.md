# Core (`elevator-common-core`)

The elevator model — pure, no Pekko, no I/O. Everything else builds on it.

Split along one seam, **data vs. behaviour over time**:

| Package | Holds | Nature |
|---------|-------|--------|
| `core.domain` | `Floor`, `ElevatorOrder`, `ElevatorState`, `Direction`, `Motion`, `Command` | immutable values; no side effects |
| `core.engine` | `Engine` / `SlowEngine` / `FastEngine`, `Elevator` | the motor that moves an elevator, plus the aggregate driving it |

`engine` depends on `domain`; `domain` depends on nothing.

## Why

`Engine` alone models behaviour over time: `cost` busy-spins to simulate travel —
the system's only pacing ([PROTOCOL.md §4](PROTOCOL.md)). The seam is enforced by
imports: only the app layer (`Operator`, `ElevatorApp`) touches `core.engine`;
every `elevator-common` submodule is domain-only.

## Files

| File | Contents |
|------|----------|
| `core/domain/Floor.scala` | `Floor`, `FloorNum`, `HasFloorNum` |
| `core/domain/ElevatorOrder.scala` | `ElevatorOrder`, `OrderTag`, `HasOrderTag` |
| `core/domain/ElevatorState.scala` | `ElevatorState`, `ElevatorName`, `Direction`, `Motion`, `Command` |
| `core/engine/Engine.scala` | `Engine`, `SlowEngine`, `FastEngine`, `Elevator` |

## Model

`Elevator = name + Engine + ElevatorState`. `move(command)` runs it through the
engine, returning a new immutable `Elevator`. `Command` = `Go(Direction)` or
`Stop()`. `SlowEngine` (~500M spins/step) is realistic; `FastEngine` (~2K) is for
tests. Scheduling lives in `elevator-common-strategy` ([§5](PROTOCOL.md)).

## Status

Four files reviewed. Tests: `.../core/ElevatorTests.scala`.
