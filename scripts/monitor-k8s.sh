#!/usr/bin/env bash
# Launch the console monitor against the kind cluster (k8s-only setup).
#
# The console needs two cluster services on the host; this ensures both port-forwards and
# wires the console to them, so you don't have to remember ports or env vars:
#   - Kafka  via the EXTERNAL listener (advertises localhost:9094) -> forward 9094:9094
#     (no /etc/hosts hack; see k8s/kafka.yaml). Console reads it via KAFKA_BOOTSTRAP.
#   - elevator-api actuator on :8080 -> forward 8080:8080. The monitor polls
#     http://localhost:8080/actuator/health and only leaves "waiting for backend" when it's UP.
# Ctrl-C exits the TUI; the port-forwards keep running in the background.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"   # so the console's relative log defaults (logs/app.log, logs/api.log) resolve

CONSOLE="$ROOT/elevator-console/target/release/elevator-console"
[ -x "$CONSOLE" ] || CONSOLE="$ROOT/elevator-console/target/debug/elevator-console"
[ -x "$CONSOLE" ] || { echo "build the console first: (cd elevator-console && cargo build)"; exit 1; }

ensure_forward() {  # <svc> <local:remote>
  local svc="$1" map="$2"
  if ps -C kubectl -o args= 2>/dev/null | grep -q "port-forward svc/$svc $map"; then
    echo "==> svc/$svc $map already forwarded"
  else
    echo "==> port-forward svc/$svc $map"
    nohup kubectl port-forward "svc/$svc" "$map" >"$ROOT/logs/pf-$svc.log" 2>&1 &
    disown
  fi
}

# Stream a deployment's pod logs into logs/<name>.log so the monitor's Logs view (which
# tails files) shows live k8s logs. Idempotent: skip if already streaming.
stream_logs() {  # <deployment> <logfile>
  local dep="$1" out="$2"
  if ps -C kubectl -o args= 2>/dev/null | grep -q "logs -f deployment/$dep"; then
    echo "==> logs deployment/$dep already streaming"
  else
    echo "==> stream deployment/$dep -> ${out#$ROOT/}"
    nohup kubectl logs -f --tail=200 "deployment/$dep" >"$out" 2>&1 &
    disown
  fi
}

mkdir -p "$ROOT/logs"
ensure_forward kafka        9094:9094
ensure_forward elevator-api 8080:8080
stream_logs elevator-app "$ROOT/logs/app.log"
stream_logs elevator-api "$ROOT/logs/api.log"

# Wait for both host ports to accept connections before launching the TUI.
for hp in 9094 8080; do
  for i in $(seq 1 20); do
    (echo > "/dev/tcp/127.0.0.1/$hp") >/dev/null 2>&1 && break
    sleep 1
    [ "$i" = 20 ] && { echo "    port $hp never came up — is the cluster running? (kubectl get pods)"; exit 1; }
  done
done

export KAFKA_BOOTSTRAP=localhost:9094
export HEALTH_URL=http://localhost:8080/actuator/health

# The full-screen TUI needs a real terminal. If stdout is a TTY, launch it. Otherwise
# (e.g. run from the in-session `!` bash, which captures output) it would panic on init,
# so run a quick selftest + the headless `watch` live view instead.
if [ -t 1 ]; then
  echo "==> launching monitor (Ctrl-C exits; port-forwards keep running)"
  exec "$CONSOLE" monitor "$@"
else
  echo "==> no TTY here — the full-screen monitor needs a real terminal window."
  echo "    Running a headless check + live view instead (open a real terminal for the chart)."
  "$CONSOLE" selftest --duration 5 || true
  echo "==> headless live view (Ctrl-C to stop):"
  exec "$CONSOLE" watch
fi
