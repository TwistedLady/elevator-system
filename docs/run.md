# Run it

A working backend (no web frontend) proving the full path: `POST /api/order` → Kafka →
[actors](actors.md) → Kafka state → live view. Needs Kafka **and** Postgres, both from
`docker-compose.demo.yml`.

## Demo

```bash
scripts/demo-up.sh          # infra + both JVMs, seeds a fleet (e1..eN), opens the chart
scripts/demo.sh lift-a 5    # order lift-a to floor 5, poll until it arrives (PASS/FAIL)
scripts/demo-down.sh        # stop everything

# durable read-model (survives a restart, unlike the Kafka cache):
docker exec -i elevator-demo-postgres psql -U elevator -d elevator -c \
  "SELECT elevator_name, floor, direction, motion FROM elevator_state_view;"
```

`demo-up.sh` knobs: `PROFILE=test|prod` · `ELEVATORS=N` · `FLEET_FILE=scripts/fleet.txt` ·
`SEED=N` · `SEED_MAX_FLOOR=20` · `NO_UI=1`.

> **Why it seeds a fleet:** Kafka has no volume, so a restart wipes `elevator-state` (and the
> api cache) — the chart would start blank though the Postgres journal still has the actors.
> So `demo-up.sh` fires a burst of orders after boot.

> Schema is created from `db/init/` on first Postgres start. Recreate from scratch:
> `docker compose -p elevator-demo -f docker-compose.demo.yml down -v`.

## Watch live

The **Rust console** is the rich view — a TUI with tabs: chart · trend · order · sim · health ·
logs · k8s. It talks to the system **only over HTTP** (orders `POST /api/order`, live state via
SSE `GET /api/elevator/stream`).

```bash
cd elevator-console-cli && cargo run -- monitor      # or: elevator-console-cli watch  (stream to stdout)
```

Bash fallback chart: `scripts/monitor.sh` (auto-discovers elevators; `MAX_FLOOR`, `INTERVAL`,
`FRAMES=N`). Logs while running: `.run/app.log`, `.run/api.log`.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/order` | Body `{"elevatorName":"lift-a","floor":5}` → publishes a command. `tag` optional (auto UUID). |
| `GET` | `/api/elevator/{name}` | Latest state JSON, or `404`. |
| `GET` | `/api/elevator` | Latest state of every known elevator. |
| `GET` | `/api/elevator/stream` | **SSE** live state stream. |
| `GET` | `/api/order/{tag}` | Order lifecycle `PROGRESS → DONE` from `order_status`, or `404`. |
| `GET` | `/actuator/health` | Health incl. Kafka readiness. Default port **8080**. |

## Test

```bash
mvn test          # unit: logic, strategy, event evolution, actor recovery, serialization
mvn verify        # + Testcontainers IT (ElevatorStateFlowIT: Spring + Kafka + Postgres)

# the console is the end-to-end harness (HTTP + kubectl log cross-check, no Kafka):
elevator-console-cli selftest              # api health UP + state flowing → PASS/FAIL + exit code
elevator-console-cli itest --count 20 --timeout 90   # send N orders, poll each to DONE, cross-check pod logs
#   → logs/itest-report.{json,md}; exit 0 only if 0 lost + every tag DONE + car moved
```

**Commit gate:** a pre-commit hook runs `itest` and blocks the commit on failure. Enable once:
`git config core.hooksPath scripts/hooks`. It skips (not blocks) when the kind cluster is
unreachable, or with `SKIP_ITEST=1 git commit …`.

## Config

- **Order validation** — `POST /api/order` is validated against `elevator.max-floor` (15) and
  `elevator.elevators` (e1..e10); a bad floor/unknown elevator returns **400**. Env override:
  `ELEVATOR_MAXFLOOR` / `ELEVATOR_ELEVATORS`.
- **Fast / slow mode (k8s)** — the app runs `FastOperator` or `SlowOperator`, chosen by which
  ConfigMap `envFrom` points at (`…-config-fast` / `…-config-slow`). Flip from the console's
  **K8s tab** (`f` / `s`) — it swaps the ConfigMap and rolls the pod via `kubectl`.

## Design note

The monitor shows `motion=MOVING` even at the destination. Not a bug: on arrival the Controller
clears the request immediately, so `Policy` never issues the `Stop()` that would publish a
`STOPPED`. "Arrived" means `floor == target`. Emitting a clean `STOPPED` on arrival is a nice
next exercise.
