# Core (`elevator-common-core`)

The elevator model — pure, no Pekko, no I/O. Everything else builds on it. Split along one
seam: **data vs. behaviour over time**.

| Package | Holds | Nature |
|---|---|---|
| `core.domain` | `Floor`, `Call`, `Order`, `ElevatorState`, `Direction`, `Motion`, `Command` | immutable values, no side effects |
| `core.engine` | `Engine` / `SlowEngine` / `FastEngine`, `Elevator` | the motor that moves a car |

`engine` depends on `domain`; `domain` depends on nothing. Only the app layer (`Operator`,
`ElevatorApp`) touches `core.engine`; every other `elevator-common-*` module is domain-only.

## Model

`Elevator = name + Engine + ElevatorState`. `move(command)` runs it through the engine and
returns a new immutable `Elevator`. `Command` = `Go(Direction)` or `Stop()`.

`Engine.cost` busy-spins to simulate travel — **the system's only pacing**. `SlowEngine`
(~500M spins/step) is realistic; `FastEngine` (~2K) is for tests and the demo. Scheduling is
separate, in `elevator-common-strategy` — see [scheduling.md](scheduling.md).

## Files

| File | Contents |
|---|---|
| `core/domain/Floor.scala` | `Floor`, `FloorNum`, `HasFloorNum` |
| `core/domain/Call.scala` | `Call`, `CallId`, `PassengerId`, `HasCallId` (a user action, with optional passenger) |
| `core/domain/Order.scala` | `Order`, `OrderId`, `HasOrderId` (same-floor calls grouped into one stop; counts riders vs. anonymous) |
| `core/domain/ElevatorState.scala` | `ElevatorState`, `ElevatorName`, `Direction`, `Motion`, `Command` |
| `core/engine/Engine.scala` | `Engine`, `SlowEngine`, `FastEngine`, `Elevator` |

Tests: `.../core/ElevatorTests.scala`.
