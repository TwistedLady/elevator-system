#!/usr/bin/env bash
# Bring up the full demo backend: Kafka (docker) + elevator-app + elevator-api (host JVMs).
# No Oracle. State lives in memory. Usage: scripts/demo-up.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
RUN_DIR="$ROOT/.run"
mkdir -p "$RUN_DIR"

MVN="${MVN:-mvn}"
APP_JAR="elevator-app/target/elevator-app-1.0-SNAPSHOT.jar"
API_JAR="elevator-api/target/elevator-api-1.0-SNAPSHOT.jar"

echo "==> 1/4 Starting Kafka (docker compose)…"
docker compose -p elevator-demo -f docker-compose.demo.yml up -d

echo "==> waiting for Kafka on localhost:9092…"
for i in $(seq 1 60); do
  if (echo > /dev/tcp/127.0.0.1/9092) >/dev/null 2>&1; then echo "    Kafka is up."; break; fi
  sleep 1
  [ "$i" = "60" ] && { echo "    Kafka did not come up"; exit 1; }
done

echo "==> 2/4 Building jars (skip tests)…"
"$MVN" -q -DskipTests package

echo "==> 3/4 Launching elevator-app (in-memory journal)…"
java -jar "$APP_JAR" > "$RUN_DIR/app.log" 2>&1 &
echo $! > "$RUN_DIR/app.pid"
echo "    pid $(cat "$RUN_DIR/app.pid")  (logs: .run/app.log)"

echo "==> 4/4 Launching elevator-api (Spring, :8080)…"
java -jar "$API_JAR" > "$RUN_DIR/api.log" 2>&1 &
echo $! > "$RUN_DIR/api.pid"
echo "    pid $(cat "$RUN_DIR/api.pid")  (logs: .run/api.log)"

echo "==> waiting for API on http://localhost:8080…"
for i in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/elevator/__probe__ || true)"
  # 404 means the endpoint is live (no state yet); that's "ready".
  if [ "$code" = "404" ] || [ "$code" = "200" ]; then echo "    API is up."; break; fi
  sleep 1
  [ "$i" = "60" ] && { echo "    API did not come up — see .run/api.log"; exit 1; }
done

echo
echo "Backend is up. Try:  scripts/demo.sh lift-a 5"
echo "Tear down with:      scripts/demo-down.sh"
