# Elevator System

An event-sourced elevator simulator, rebuilt clean as a learning project for the JVM:
**Scala 3** for the domain, **Apache Pekko** (typed actors, cluster sharding, event
sourcing) for the runtime, **Apache Kafka** as the command/state bus, and **Spring Boot**
as the HTTP edge.

This repository is built up in small, deliberate commits — each one a self-contained step —
rather than dropped in all at once. Read the history to follow the architecture coming together.

## Planned shape

```
HTTP POST ─► Kafka(commands) ─► Coordinator ─► Controller ─► Operator
                                                   │ each floor move
HTTP GET  ◄─ monitor ◄─ Kafka(state) ◄─────────────┘
```

| Module                 | Stack  | Role                                            |
|------------------------|--------|-------------------------------------------------|
| `elevator-common-core` | Scala 3 | Pure domain: elevator, floors, scheduling policy |
| `elevator-common-dto`  | Scala 3 | Messages shared across the wire                  |
| `elevator-app`         | Pekko  | The brain: event-sourced actors                  |
| `elevator-api`         | Spring | HTTP edge: order an elevator, monitor its state  |

## Status

Work in progress — see the commit history for the story so far.
