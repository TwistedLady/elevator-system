# Run it

A working backend (no web frontend) proving the full path: `POST /api/order` → Kafka →
[actors](actors.md) → Kafka state → live view. Needs Kafka **and** Postgres, both from
`docker-compose.demo.yml`.

## Demo

The whole backend runs in containers — no host JVMs, no shell scripts:

```bash
docker compose -f docker-compose.demo.yml up --build          # kafka + postgres + app + api
docker compose -f docker-compose.demo.yml --profile seed up    # …and seed a fleet of orders (one-shot Job)
docker compose -f docker-compose.demo.yml down                 # stop everything (add -v to wipe data)
docker compose -f docker-compose.demo.yml logs -f app api      # follow the JVM logs

# durable read-model (survives a restart, unlike the Kafka cache):
docker exec -i elevator-demo-postgres psql -U elevator -d elevator -c \
  "SELECT elevator_name, floor, direction, motion FROM elevator_state_view;"
```

Seed knobs live in the `seed` service's `command:` in `docker-compose.demo.yml`
(`--elevator-count`, `--max-floor`, `--count`).

> **Why seed:** Kafka has no volume, so a restart wipes `elevator-state` (and the api cache) — the
> chart starts blank though the Postgres journal still has the actors. The `seed` profile fires a
> burst of orders after boot.

> Schema is created from `db/init/` on first Postgres start. Recreate from scratch:
> `docker compose -p elevator-demo -f docker-compose.demo.yml down -v`.

## Watch live

The **Rust console** is the rich view — a TUI with tabs: chart · trend · order · sim · health ·
logs · k8s. It talks to the system **only over HTTP** (orders `POST /api/order`, live state via
SSE `GET /api/elevator/stream`).

```bash
cd elevator-console-cli && cargo run -- monitor      # or: elevator-console-cli watch  (stream to stdout)
```

Logs while running: `docker compose -f docker-compose.demo.yml logs -f app api`.

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

All app params live in **one** ConfigMap, `elevator-config` (rendered from `charts/elevator` —
values `config.*`), mounted by both
the app and the api as env vars *and* as files under `/etc/elevator-config`. Editing the ConfigMap
hot-reloads the tunables in-process — no pod restart (kubelet file sync + a ~5s in-app poll).

- **Order validation** — `POST /api/order` is validated against `ELEVATOR_MAXFLOOR` (15) and
  `ELEVATOR_ELEVATORS` (e1..e10); a bad floor/unknown elevator returns **400**. The api owns these
  limits; the app never validates. `GET /api/config` exposes them, and both consoles fetch it
  (no hardcoded floors/fleet). Edit the ConfigMap and validation updates live. A missing ConfigMap
  makes the api **fail to start** (no baked-in default).
- **Engine — fast / slow** — the app reads `ELEVATOR_ENGINE` (`fast` / `slow`). Flip it from the
  console's **K8s tab** (`f` / `s`) or `kubectl edit configmap elevator-config`. The app hot-swaps
  the engine on the next move — **no rollout, no restart**.
- **BI on / off** — `ELEVATOR_BI_ENABLED` toggles the Spark analytics layer (read at api startup via
  `@ConditionalOnProperty`). On kind, toggle it with Helm — `skaffold run -p bi`, or
  `helm upgrade elevator charts/elevator --reuse-values --set bi.enabled=false` ([cluster.md](cluster.md)):
  Helm flips the flag and applies/deletes the Spark stats driver (which writes the Parquet read-model
  the api reads via DuckDB) in one step. When off,
  `GET /api/mileage` & `/api/served` are **not created (404)**, `/actuator/health` shows the `bi`
  component **DISABLED** (overall stays **UP**), and both consoles hide the **Stats** tab.

## Design note

The monitor shows `motion=MOVING` even at the destination. Not a bug: on arrival the Controller
clears the request immediately, so `Policy` never issues the `Stop()` that would publish a
`STOPPED`. "Arrived" means `floor == target`. Emitting a clean `STOPPED` on arrival is a nice
next exercise.
