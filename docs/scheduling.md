# Scheduling

How the [Controller](actors.md) picks the next move. Pure function
`NextFloorStrategy.default` — a simple **SCAN**: keep going the same way while a target is
ahead, else reverse, else stop.

```scala
if targets.contains(current) then Stop()               // arrived → stop, serve floor
else if targetAhead(current, dir, targets) then Go(dir)
else if targets.nonEmpty then Go(dir.swap)             // turn around
else Stop()                                            // nothing to do
```

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Moving: order added (target ahead)
    Moving --> Moving: target ahead → Go(dir)
    Moving --> Reversing: none ahead, some behind
    Reversing --> Moving: Go(dir.swap)
    Moving --> Stopping: target == current → Stop()
    Stopping --> Idle: no orders left
    Stopping --> Moving: orders remain
```

Each chosen move is then gated by the [suspender](suspender.md) before the Controller issues it.

Source: `elevator-common-strategy/.../NextFloorStrategy.scala`. Its sibling
`GroupCallsStrategy` groups same-floor calls into orders (`order id = f(elevator, floor)`, so
later same-floor calls attach to the same order) — see [protocol.md](protocol.md).
