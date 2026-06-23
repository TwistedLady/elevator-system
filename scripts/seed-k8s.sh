#!/usr/bin/env bash
# Seed the kind cluster with random orders for the e1..e10 fleet across the 15-floor building.
# Ensures the cluster Kafka port-forward (9094), then fires the simulator.
#   Usage: scripts/seed-k8s.sh [COUNT]      (default 300)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
COUNT="${1:-300}"

CONSOLE="$ROOT/elevator-console/target/release/elevator-console"
[ -x "$CONSOLE" ] || CONSOLE="$ROOT/elevator-console/target/debug/elevator-console"
[ -x "$CONSOLE" ] || { echo "build the console first: (cd elevator-console && cargo build)"; exit 1; }

mkdir -p "$ROOT/logs"
if ! ps -C kubectl -o args= 2>/dev/null | grep -q "port-forward svc/kafka 9094:9094"; then
  echo "==> port-forward svc/kafka 9094:9094"
  nohup kubectl port-forward svc/kafka 9094:9094 >"$ROOT/logs/pf-kafka.log" 2>&1 &
  disown
fi
for i in $(seq 1 20); do
  (echo > /dev/tcp/127.0.0.1/9094) 2>/dev/null && break
  sleep 1
  [ "$i" = 20 ] && { echo "    kafka :9094 never came up — is the cluster running? (kubectl get pods)"; exit 1; }
done

echo "==> seeding $COUNT orders: e1..e10 fleet, 15 floors"
exec env KAFKA_BOOTSTRAP=localhost:9094 \
     "$CONSOLE" simulate --elevator-count 10 --max-floor 15 --count "$COUNT"
