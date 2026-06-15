#!/usr/bin/env bash
# Stop the host JVMs and the Kafka containers started by demo-up.sh.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
RUN_DIR="$ROOT/.run"

for name in api app; do
  pidfile="$RUN_DIR/$name.pid"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "==> stopping $name (pid $pid)"
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  fi
done

echo "==> stopping Kafka (docker compose down)"
docker compose -p elevator-demo -f docker-compose.demo.yml down

echo "Done."
