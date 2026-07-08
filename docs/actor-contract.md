# Actor Contract

Four actors, one per elevator. The first three are **event-sourced** (state is rebuilt from a stored
event log); the **Operator** is **stateless**. Actors take **commands**, not method calls.

`[cmd]` command in · `[evt]` event stored · `[pub]` Kafka publish · `→` sends to

## Coordinator — owns call status
State: `Map[CallId, FloorNum]`

| `[cmd]` | `[evt]` | `[pub]` | `→` |
|---|---|---|---|
| `Handle(List[Call])` | `CallReceived` | call PROGRESS | Manager.`Combine` |
| `AssignOrder(CallId, OrderId)` | `CallAssigned` | — | — |
| `MarkDone(CallId)` | `CallDone` | call DONE | — |

## Manager — owns call ↔ order
State: `Map[OrderId, Order]`

| `[cmd]` | `[evt]` | `[pub]` | `→` |
|---|---|---|---|
| `Combine(List[Call])` | `OrderCreated` / `OrderExtended` | order PROGRESS | Coordinator.`AssignOrder`, Controller.`Process` |
| `MarkDone(OrderId)` | `OrderDone` | order DONE | Coordinator.`MarkDone` |

## Controller — owns direction
State: `(waiting: Boolean, ElevatorState, Set[Order])`

| `[cmd]` | `[evt]` | `[pub]` | `→` |
|---|---|---|---|
| `Process(Set[Order])` | `OrderAccepted` | — | self `ChooseNext` |
| `ChooseNext(Set[Order])` | `WaitingSet(true)` | — | Operator.`Move` |
| `MarkExecuted(ElevatorState)` | `WaitingSet(false)`, `ElevatorStateUpdated` | elevator | Manager.`MarkDone` (reached floor) |

## Operator — owns move · stateless
| `[cmd]` | `[evt]` | `[pub]` | `→` |
|---|---|---|---|
| `Move(ElevatorName, ElevatorState, Command)` | — | — | Controller.`MarkExecuted` |

## Rules

- **Command** = a message sent to an actor (may be rejected). **Event** = a fact it persisted
  (replayed on restart to rebuild state). **Publish** = a Kafka message for read tables / console /
  BI — not stored.
- **No DTOs inside actors.** The `CallConsumer` maps the wire `CallDto` → `Call` at the edge; actors
  speak only domain types.
- **`ChooseNext` + `WaitingSet`** express the move loop as messages, so a crash mid-move re-issues the
  move on recovery. A blocking loop cannot.

See [actors.md](actors.md) for the flow diagram, [protocol.md](protocol.md) for the full sequence.
