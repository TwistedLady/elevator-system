#!/usr/bin/env bash
# Collect the BI job logs into the repo's logs/bi/ dir (matches the project's logs/ convention).
# Grabs:
#   - live stdout of the driver + every executor pod (kubectl logs)
#   - the durable per-pod rolling log files persisted on the node's hostPath volume
#   - a copy of the Spark event logs (job history)
# Pass -f to instead follow the driver log live.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/logs/bi"
NODE="elevator-control-plane"   # kind single node

if [ "${1:-}" = "-f" ]; then
  exec kubectl logs -f deploy/elevator-mileage
fi

mkdir -p "$OUT"
echo "==> collecting current pod logs -> $OUT"
for p in $(kubectl get pods -l app=elevator-bi -o name); do
  name="${p#pod/}"
  kubectl logs "$name" >"$OUT/${name}.stdout.log" 2>/dev/null || true
  echo "    $name.stdout.log"
done

echo "==> copying persisted rolling logs + event logs from node $NODE"
docker exec "$NODE" sh -c 'cd /mnt/elevator-bi-logs 2>/dev/null && tar -cf - . 2>/dev/null' \
  | tar -xf - -C "$OUT" 2>/dev/null && echo "    rolling logs -> $OUT" || echo "    (no persisted rolling logs yet)"
docker exec "$NODE" sh -c 'cd /mnt/elevator-bi-checkpoint/spark-events 2>/dev/null && tar -cf - . 2>/dev/null' \
  | tar -xf - -C "$OUT" 2>/dev/null && echo "    spark-events -> $OUT" || echo "    (no event logs yet)"

echo "==> done. Files in $OUT:"
ls -la "$OUT"
