# Demo: Pekko + Kafka + Spring + Postgres, no Oracle

A working backend (no frontend) that proves the full path:

```
curl POST /api/order
   └─► Kafka topic: elevator-commands
          └─► Coordinator (dedup)  ─►  Controller (scheduling)  ─►  Operator (one floor/step)
                       │ persist events                                    │ publishes each move
                       ▼                                                    ▼
              R2DBC journal (Postgres)                            Kafka topic: elevator-state
                       │ eventsBySlices                                     │
                       ▼                                          curl GET /api/elevator/{name}
            ElevatorStateProjection                              ◄── StateStore (in-memory cache)
                       │ UPSERT
                       ▼
            elevator_state_view  (durable read-model — query with psql)
```

Persistence is now Pekko's **reactive R2DBC Postgres journal**, so actor state **survives
restarts** (rebuilt by replaying events), and a projection maintains the durable
`elevator_state_view` read-model. You need Kafka **and** Postgres — both come up via
`docker-compose.demo.yml`.

## Run it

```bash
scripts/demo-up.sh          # infra + both JVMs, then seeds a fleet and opens the live chart
scripts/demo.sh lift-a 5    # order lift-a to floor 5, then monitor until it arrives (pass/fail)
scripts/demo-down.sh        # stop everything

# the durable read-model (survives an app restart, unlike the Kafka cache):
docker exec -i elevator-demo-postgres psql -U elevator -d elevator -c \
  "SELECT elevator_name, floor, direction, motion FROM elevator_state_view;"
```

> **Why demo-up.sh seeds a fleet:** Kafka has no volume in `docker-compose.demo.yml`, so a
> restart wipes the `elevator-state` topic (and the api's in-memory cache) — the chart would
> start empty even though the Postgres journal still has the actors. So `demo-up.sh` fires a
> burst of orders after boot. Configure it:
>
> ```bash
> ELEVATORS=8 scripts/demo-up.sh                   # generate e1..e8 (default 4)
> cp scripts/fleet.example.txt scripts/fleet.txt   # or name them in a file (auto-detected)
> SEED=1000 SEED_MAX_FLOOR=20 scripts/demo-up.sh   # heavier load / taller building
> NO_UI=1 scripts/demo-up.sh                       # don't open the chart at the end
> ```

> Schema (journal + projection tables + `elevator_state_view`) is created by the Postgres
> container from `db/init/` on first start. To recreate it from scratch, wipe the volume:
> `docker compose -p elevator-demo -f docker-compose.demo.yml down -v`.

### Watch it live

The richer option is the **Rust console** (`elevator-console`): a retro TUI with tabs for
the building chart, floor-over-time, actuator health, and a live log viewer — and you can
order / bulk-`sim` from inside it. See `elevator-console/README.md`.

The original quick bash chart still works too:

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
| `GET`  | `/api/order/{tag}` | Was the order processed? `{tag,elevatorName,floor,status,processed}` from the `order_status` read-model, or `404` if the tag is unknown. |

## A design note worth knowing

The monitor reports `motion=MOVING` even at the destination. That's not a bug in the demo —
it's how the actors behave: when the elevator **arrives** at a requested floor, the Controller
clears the request immediately, so `Policy` never issues the `STOP()` that would publish a
`STOPPED` state. "Arrived" therefore means `floor == target`. Making the elevator emit a clean
`STOPPED` on arrival is a small, interesting domain change — a good next exercise.

## Notes / shortcuts taken (so they're not invisible)

- Single-node Pekko cluster with a **durable R2DBC Postgres journal** → actor state **survives
  restarts** (rebuilt by replaying events; proven by the recovery tests in `elevator-app`). The
  single node carries both `write-model` and `read-model` cluster roles, so the projection runs here.
- Controller uses the **fast** engine so floors advance ~every 500ms (the tick rate) instead of
  the slow engine's deliberate CPU burn.
- Kafka runs in KRaft mode (no ZooKeeper) under an isolated compose project, `elevator-demo`.
- **Postgres** backs the Pekko **event journal + the read-side projection** (compose service
  `postgres`, and `k8s/postgres.yaml` as a StatefulSet with a PVC). Its data lives in the `pgdata`
  volume so it survives restarts (`psql postgresql://elevator:elevator@localhost:5432/elevator`).
  The reactive R2DBC Postgres driver is added explicitly — `pekko-persistence-r2dbc` ships only
  the SPI/pool, not a driver.
- **Throughput / backlog:** the Controller serves at most **one order per tick (~500ms)** and
  does *not* batch multiple orders at the same floor — each stop clears only one. Fire tens of
  thousands of orders (`sim`) and the lifts grind floor-by-floor through the backlog, appearing
  "stuck" on busy floors. Batching same-floor orders on arrival is the natural fix (a good
  exercise — a real lift opens its doors once for everyone on that floor).
