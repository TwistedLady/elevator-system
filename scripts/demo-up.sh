#!/usr/bin/env bash
# Bring up the full demo backend: Kafka + Postgres (docker) + elevator-app + elevator-api (host JVMs).
# Actor state persists in Postgres (R2DBC journal); read-model projection too. Usage: scripts/demo-up.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
RUN_DIR="$ROOT/.run"
mkdir -p "$RUN_DIR"

# This demo runs its own Kafka on localhost:9092. The console now defaults to the k8s
# cluster (localhost:9094), so pin it back to the demo broker for the seed/monitor below.
export KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"

# All logs land in ./logs: app+api host JVMs write app.log/api.log here, and the Kafka +
# Postgres containers bind-mount their log dirs into ./logs/kafka and ./logs/postgres.
# 0777 so the Postgres server (container uid 999) can write into its bind-mounted subdir.
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR/kafka" "$LOG_DIR/postgres"
chmod 777 "$LOG_DIR/kafka" "$LOG_DIR/postgres"

# Logging profile for the two JVMs:
#   test (default) → verbose, human-readable console (TRACE our code, DEBUG frameworks)
#   prod           → lean INFO/WARN, one-line JSON to console + rolling logs/{app,api}.json
PROFILE="${PROFILE:-test}"
case "$PROFILE" in test|prod) ;; *) echo "PROFILE must be 'test' or 'prod' (got '$PROFILE')"; exit 1;; esac
echo "==> logging profile: $PROFILE"

MVN="${MVN:-mvn}"
APP_JAR="elevator-app/target/elevator-app-1.0-SNAPSHOT.jar"
API_JAR="elevator-api/target/elevator-api-1.0-SNAPSHOT.jar"

echo "==> 1/4 Starting Kafka + Postgres (docker compose)…"
docker compose -p elevator-demo -f docker-compose.demo.yml up -d

echo "==> waiting for Kafka on localhost:9092…"
for i in $(seq 1 60); do
  if (echo > /dev/tcp/127.0.0.1/9092) >/dev/null 2>&1; then echo "    Kafka is up."; break; fi
  sleep 1
  [ "$i" = "60" ] && { echo "    Kafka did not come up"; exit 1; }
done

# The app now needs Postgres at boot (R2DBC journal + projection). Wait for it to accept
# connections (the container also has to finish running ./db/init on a fresh volume).
echo "==> waiting for Postgres on localhost:5432…"
for i in $(seq 1 60); do
  if docker exec elevator-demo-postgres pg_isready -U elevator -d elevator >/dev/null 2>&1; then
    echo "    Postgres is up."; break
  fi
  sleep 1
  [ "$i" = "60" ] && { echo "    Postgres did not come up"; exit 1; }
done

echo "==> 2/4 Building jars (skip tests)…"
"$MVN" -q -DskipTests package

echo "==> 3/4 Launching elevator-app (R2DBC Postgres journal + projection)…"
# Pekko's own internal log filter stays at INFO (its default; see application.conf).
PEKKO_LOGLEVEL="${PEKKO_LOGLEVEL:-INFO}" java -Dapp.profile="$PROFILE" -jar "$APP_JAR" > "$LOG_DIR/app.log" 2>&1 &
echo $! > "$RUN_DIR/app.pid"
echo "    pid $(cat "$RUN_DIR/app.pid")  (logs: logs/app.log)"

echo "==> 4/4 Launching elevator-api (Spring, :8080)…"
SPRING_PROFILES_ACTIVE="$PROFILE" java -jar "$API_JAR" > "$LOG_DIR/api.log" 2>&1 &
echo $! > "$RUN_DIR/api.pid"
echo "    pid $(cat "$RUN_DIR/api.pid")  (logs: logs/api.log)"

echo "==> waiting for API on http://localhost:8080…"
for i in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/elevator/__probe__ || true)"
  # 404 means the endpoint is live (no state yet); that's "ready".
  if [ "$code" = "404" ] || [ "$code" = "200" ]; then echo "    API is up."; break; fi
  sleep 1
  [ "$i" = "60" ] && { echo "    API did not come up — see .run/api.log"; exit 1; }
done

echo
echo "Backend is up."

# A cold restart wipes the Kafka state topic (no volume) and the api's in-memory cache,
# so the chart would start empty. Seed a fleet of moving cars so there's something to watch.
# Fleet is either a file of names or a count:
#   FLEET_FILE=path  names file (default scripts/fleet.txt if present; see fleet.example.txt)
#   ELEVATORS=N      else generate e1..eN
#   SEED=N           orders to fire   SEED_MAX_FLOOR=N   top floor   NO_UI=1   don't open the chart
FLEET_FILE="${FLEET_FILE:-$ROOT/scripts/fleet.txt}"
ELEVATORS="${ELEVATORS:-4}"
SEED="${SEED:-300}"
SEED_MAX_FLOOR="${SEED_MAX_FLOOR:-10}"

CONSOLE="$ROOT/elevator-console/target/release/elevator-console"
[ -x "$CONSOLE" ] || CONSOLE="$ROOT/elevator-console/target/debug/elevator-console"
if [ ! -x "$CONSOLE" ]; then
  echo "==> building elevator-console (cargo)…"
  ( cd "$ROOT/elevator-console" && cargo build ) && CONSOLE="$ROOT/elevator-console/target/debug/elevator-console"
fi

if [ "$SEED" -gt 0 ] && [ -x "$CONSOLE" ]; then
  if [ -f "$FLEET_FILE" ]; then
    echo "==> seeding $SEED orders across the fleet in $FLEET_FILE…"
    fleet_args=(--elevators-file "$FLEET_FILE")
  else
    echo "==> seeding $SEED orders across $ELEVATORS elevators (e1..e$ELEVATORS)…"
    fleet_args=(--elevator-count "$ELEVATORS")
  fi
  "$CONSOLE" simulate "${fleet_args[@]}" --count "$SEED" --max-floor "$SEED_MAX_FLOOR" \
    || echo "    (seed failed — skip with SEED=0)"
fi

echo
if [ "${NO_UI:-0}" != "1" ] && [ -x "$CONSOLE" ] && [ -t 1 ]; then
  echo "==> opening the live chart (Ctrl-C exits the chart; backend keeps running)…"
  exec "$CONSOLE" monitor
else
  echo "Open the live chart with:  $CONSOLE monitor"
  echo "Or the bash chart:         scripts/monitor.sh"
fi
echo "Tear down with:            scripts/demo-down.sh"
