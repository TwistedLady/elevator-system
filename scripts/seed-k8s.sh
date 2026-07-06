#!/usr/bin/env bash
# Seed the kind cluster with random orders for the e1..e10 fleet across the 15-floor building,
# via the elevator-api HTTP edge (the console no longer speaks Kafka).
#   Usage: scripts/seed-k8s.sh [COUNT]      (default 300)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COUNT="${1:-300}"
API="${ELEVATOR_API:-https://localhost:8080}"

CONSOLE="$ROOT/elevator-console/target/release/elevator-console"
[ -x "$CONSOLE" ] || CONSOLE="$ROOT/elevator-console/target/debug/elevator-console"
[ -x "$CONSOLE" ] || { echo "build the console first: (cd elevator-console && cargo build --release)"; exit 1; }

mkdir -p "$ROOT/logs"
if ! ps -C kubectl -o args= 2>/dev/null | grep -q "port-forward svc/elevator-api 8080:8080"; then
  echo "==> port-forward svc/elevator-api 8080:8080"
  nohup kubectl port-forward svc/elevator-api 8080:8080 >"$ROOT/logs/pf-api.log" 2>&1 &
  disown
fi
for i in $(seq 1 30); do
  curl -skf -o /dev/null "$API/actuator/health" && break
  sleep 1
  [ "$i" = 30 ] && { echo "    api never came up at $API — is the cluster running? (kubectl get pods)"; exit 1; }
done

echo "==> seeding $COUNT orders: e1..e10 fleet, 15 floors (via API $API)"
exec env ELEVATOR_API="$API" \
     "$CONSOLE" simulate --elevator-count 10 --max-floor 15 --count "$COUNT"
