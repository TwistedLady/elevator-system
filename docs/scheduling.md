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

Source: `elevator-common-strategy/.../NextFloorStrategy.scala`.
