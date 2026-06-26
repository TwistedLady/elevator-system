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
stream_logs() {  # <label-selector> <logfile>
  local sel="$1" out="$2"
  if pgrep -f "logs -f .* -l ${sel}" >/dev/null 2>&1; then
    echo "==> logs -l ${sel} already streaming"
    return
  fi
  echo "==> stream pods -l ${sel} -> ${out#$ROOT/}  (re-attaches across rollouts)"
  # `kubectl logs -f` attaches only to the pods that exist when it starts and EXITS when they go
  # away — e.g. a rolling restart after a ConfigMap switch. Without re-attaching, the file froze
  # on the departing pod's last line ("Remoting shut down") and the console showed stale logs as
  # if the app had disconnected. The loop re-runs it against whatever pods exist now (both
  # replicas, and the new ones once a roll completes).
  nohup bash -c "while true; do kubectl logs -f --tail=50 --max-log-requests=20 -l '${sel}' >>'${out}' 2>/dev/null; sleep 1; done" >/dev/null 2>&1 &
  disown
}

mkdir -p "$ROOT/logs"
ensure_forward kafka        9094:9094
ensure_forward elevator-api 8080:8080
stream_logs app=elevator-app "$ROOT/logs/app.log"
stream_logs app=elevator-api "$ROOT/logs/api.log"

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
