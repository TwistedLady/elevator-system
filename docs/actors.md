# The four actors

One elevator = **four actors**. Three **remember** (event-sourced); the **Operator** is a dumb worker.

| Actor | Remembers? | Owns |
|---|---|---|
| **Coordinator** | yes | call status |
| **Manager** | yes | call ↔ order |
| **Controller** | yes | direction (movement) |
| **Operator** | no | one move |

## One call, start to finish

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka
    participant CC as CallConsumer
    participant Co as Coordinator
    participant Mg as Manager
    participant Ct as Controller
    participant Op as Operator

    K->>CC: CallDto
    CC->>Co: Handle(List[Call])
    Note over Co: store CallReceived<br/>publish call = PROGRESS
    Co->>Mg: Combine(calls)
    Note over Mg: group by floor → Order<br/>store OrderCreated / OrderExtended<br/>publish order = PROGRESS
    Mg->>Co: AssignOrder(callId, orderId)
    Mg->>Ct: Process(orders)

    loop until the target floor is reached
        Ct->>Op: Move(state, cmd)
        Op-->>Ct: MarkExecuted(newState)
        Note over Ct: at the floor? → orders there are served
    end

    Ct->>Mg: MarkDone(orderId)
    Note over Mg: store OrderDone<br/>publish order = DONE
    Mg->>Co: MarkDone(callId)
    Note over Co: store CallDone<br/>publish call = DONE
```

## Who talks to whom

```mermaid
flowchart LR
    K(["Kafka<br/>elevator-calls"]) -->|CallConsumer maps → Call| Co
    Co["Coordinator"] -->|Combine| Mg["Manager"]
    Mg -->|AssignOrder| Co
    Mg -->|Process| Ct["Controller"]
    Ct -->|Move| Op["Operator"]
    Op -->|MarkExecuted| Ct
    Ct -->|MarkDone| Mg
    Mg -->|MarkDone| Co
```

Two things to hold onto:

- The **Controller drives its own loop** — after each move it self-sends `ChooseNext`. The
  [engine](core.md) paces it (real travel time), not a timer.
- Serving is **floor-based** — reaching a floor closes *every* order waiting there at once, which
  closes every call under them.

Every message, event and type: [actor-contract.md](actor-contract.md). Recovery guards:
[crash-recovery.md](crash-recovery.md).
