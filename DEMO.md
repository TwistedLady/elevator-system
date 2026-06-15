# Demo: Pekko + Kafka + Spring, no Oracle

A working backend (no frontend, no database) that proves the full path:

```
curl POST /api/order
   └─► Kafka topic: elevator-commands
          └─► Coordinator (dedup)  ─►  Controller (scheduling)  ─►  Operator (one floor/step)
                                                                          │ publishes each move
   curl GET /api/elevator/{name}  ◄── StateStore ◄── Kafka topic: elevator-state ◄┘
```

Persistence is Pekko's **in-memory journal** (config swap only — the domain code is unchanged),
so there is nothing to install but Kafka.

## Run it

```bash
scripts/demo-up.sh          # Kafka (docker) + elevator-app + elevator-api (host JVMs)
scripts/demo.sh lift-a 5    # order lift-a to floor 5, then monitor until it arrives (pass/fail)
scripts/demo-down.sh        # stop everything
```

### Watch it live (animated console chart)

```bash
scripts/monitor.sh              # auto-discovers every elevator; Y=floors, X=elevators
# ...and in another terminal:
scripts/demo.sh lift-a 7        # watch the car climb in the chart
```

```
  7 │   [▲]   │    ·    │
  6 │    ·    │    ·    │
  ...
  0 │    ·    │   [●]   │
    └─────────┴─────────┘
      lift-a    lift-b
```

`[▲]/[▼]` = moving up/down, `[●]` = idle. Options: `MAX_FLOOR=20`, `INTERVAL=0.5`,
`FRAMES=N` (render N frames then exit — useful for non-interactive runs). Pass names
(`scripts/monitor.sh lift-a lift-b`) to pin a fixed set instead of auto-discovering.

Logs while running: `.run/app.log`, `.run/api.log`.

## What `demo.sh` checks

1. `POST /api/order {elevatorName, floor}` → returns the generated `tag` (the order id).
2. Polls `GET /api/elevator/{name}` once a second, printing `floor / direction / motion`.
3. **PASS** when the elevator's floor equals the target; **FAIL** on timeout (default 20s,
   override with `TIMEOUT=15 scripts/demo.sh ...`).

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/order` | Body `{"elevatorName":"lift-a","floor":5}` → publishes a command. `tag` optional (auto UUID). |
| `GET`  | `/api/elevator/{name}` | Latest known state JSON, or `404` if none seen yet. |
| `GET`  | `/api/elevator` | JSON array of the latest state of every known elevator (used by `monitor.sh`). |

## A design note worth knowing

The monitor reports `motion=MOVING` even at the destination. That's not a bug in the demo —
it's how the actors behave: when the elevator **arrives** at a requested floor, the Controller
clears the request immediately, so `Policy` never issues the `STOP()` that would publish a
`STOPPED` state. "Arrived" therefore means `floor == target`. Making the elevator emit a clean
`STOPPED` on arrival is a small, interesting domain change — a good next exercise.

## Notes / shortcuts taken (so they're not invisible)

- Single-node Pekko cluster, in-memory journal → **state is lost on restart** (fine for a demo).
- Controller uses the **fast** engine so floors advance ~every 500ms (the tick rate) instead of
  the slow engine's deliberate CPU burn.
- Kafka runs in KRaft mode (no ZooKeeper) under an isolated compose project, `elevator-demo`.
