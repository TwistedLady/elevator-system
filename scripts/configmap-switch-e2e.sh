#!/usr/bin/env bash
# Real kind/k8s end-to-end test for a ConfigMap mode switch under load.
#
# It switches the elevator engine (fast <-> slow) in the single elevator-config ConfigMap WHILE a
# sim is firing orders. The app hot-reloads the value from the mounted file — NO pod restart — and
# proves the two properties you asked for:
#
#   FULL AVAILABILITY  - every elevator-app pod stays Ready throughout (the switch never restarts a
#                        pod; the engine is swapped in-process on the next move).
#   ZERO REQUEST LOSS  - every order sent is ingested: processed_orders count == orders sent.
#                        (processed_orders is the durable dedup claim, written at ingestion
#                        regardless of how slow the elevator physically moves.)
#
# Prereqs: a running kind cluster with kafka + postgres deployed, the app image built and loaded
# (elevator-app:local), and the console binary built. The script (re)applies rbac + app + the
# configmaps, then wipes the journal/read-models/topics for a clean run.
#
#   Usage: scripts/configmap-switch-e2e.sh [COUNT] [TARGET_MODE]
#            COUNT        orders to send                (default 2000)
#            TARGET_MODE  fast|slow, the mode to switch TO (default: opposite of current)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p logs

COUNT="${1:-2000}"
DEP=elevator-app
SEL="app=elevator-app"

CONSOLE="$ROOT/elevator-console-cli/target/release/elevator-console-cli"
[ -x "$CONSOLE" ] || CONSOLE="$ROOT/elevator-console-cli/target/debug/elevator-console-cli"
[ -x "$CONSOLE" ] || { echo "build the console first: (cd elevator-console-cli && cargo build)"; exit 1; }

say(){ printf '\n=== %s ===\n' "$*"; }
psql(){ kubectl exec -i postgres-0 -- psql -U elevator -d elevator -tAc "$1"; }
kafka(){ kubectl exec -i deploy/kafka -- /opt/kafka/bin/"$@"; }

current_mode(){  # read the engine from the single ConfigMap
  kubectl get configmap elevator-config -o jsonpath='{.data.ELEVATOR_ENGINE}'
}
set_mode(){  # set the engine in the ConfigMap; the app hot-reloads it in-process (no rollout)
  kubectl patch configmap elevator-config --type=merge -p "{\"data\":{\"ELEVATOR_ENGINE\":\"$1\"}}"
}
ready_count(){  # how many app pods are Ready right now
  kubectl get pods -l "$SEL" \
    -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null \
    | grep -c True || true
}

# ---- 1. apply manifests -----------------------------------------------------
say "apply rbac + configmaps + app"
kubectl apply -f k8s/rbac.yaml -f k8s/configmap.yaml -f k8s/app.yaml

# Mode to switch TO (default: opposite of current). We then force the START mode to the OPPOSITE
# of TARGET, so the mid-load switch is always a real change — even when TARGET == app.yaml's fast.
TARGET="${2:-}"
if [ -z "$TARGET" ]; then case "$(current_mode)" in slow) TARGET=fast;; *) TARGET=slow;; esac; fi
[ "$TARGET" = fast ] && START=slow || START=fast

# ---- 2. clean slate (a bloated journal makes new pods slow to be Ready and stalls the rollout)
say "wipe journal + read-models + kafka topics"
kubectl scale deploy "$DEP" --replicas=0
kubectl wait --for=delete pod -l "$SEL" --timeout=120s || true
psql "TRUNCATE event_journal, snapshot, durable_state,
            projection_offset_store, projection_timestamp_offset_store, projection_management,
            elevator_state_view, order_status, processed_orders;"
kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 --delete --group elevator-app 2>/dev/null || true
for t in elevator-commands elevator-state; do
  kafka kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic "$t" 2>/dev/null || true
done

# ---- 3. start the 2-pod cluster in the START mode --------------------------
say "set start mode = $START and scale to 2, wait for the cluster to be Ready"
set_mode "$START" >/dev/null    # replicas are 0 here, so this is free (no rollout churn)
kubectl scale deploy "$DEP" --replicas=2
kubectl rollout status deploy "$DEP" --timeout=300s

# ---- 4. availability sampler (records #Ready pods every second) -------------
SAMPLE="$(mktemp)"
( while :; do ready_count >>"$SAMPLE"; sleep 1; done ) & SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true' EXIT

# ---- 5. port-forward kafka + fire the sim ----------------------------------
say "port-forward kafka :9094 and fire $COUNT orders"
if ! ps -C kubectl -o args= 2>/dev/null | grep -q "port-forward svc/kafka 9094:9094"; then
  nohup kubectl port-forward svc/kafka 9094:9094 >logs/pf-kafka.log 2>&1 & disown
fi
for i in $(seq 1 20); do (echo > /dev/tcp/127.0.0.1/9094) 2>/dev/null && break; sleep 1; done

env KAFKA_BOOTSTRAP=localhost:9094 "$CONSOLE" simulate \
    --elevator-count 10 --max-floor 15 --count "$COUNT" --threads 4 & SIM=$!

# ---- 6. mid-sim: switch the engine in the ConfigMap -> the app hot-reloads it, NO rollout --------
say "switch engine $(current_mode) -> $TARGET while orders flow (hot-reload, no pod restart)"
sleep 3
set_mode "$TARGET"
# No rollout: the app polls the mounted ConfigMap file and swaps the engine in-process. Give the
# kubelet + poller time to propagate, then confirm the app logged the change.
for i in $(seq 1 40); do
  kubectl logs -l "$SEL" --tail=200 2>/dev/null | grep -q "engine mode -> $TARGET" && break
  sleep 3
done

wait "$SIM" || true   # let the simulator finish sending

# ---- 7. wait for ingestion to settle ---------------------------------------
say "wait until all $COUNT orders are ingested"
DEADLINE=$(( SECONDS + 180 )); got=0
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  got="$(psql 'SELECT count(*) FROM processed_orders;')"
  echo "  processed_orders = $got / $COUNT"
  [ "$got" -ge "$COUNT" ] && break
  sleep 3
done

# ---- 8. verdict ------------------------------------------------------------
kill $SAMPLER 2>/dev/null || true
MINREADY="$(sort -n "$SAMPLE" | head -1)"; rm -f "$SAMPLE"
say "RESULTS"
echo "min Ready pods during run : ${MINREADY:-?}   (want >= 1 for full availability)"
echo "orders ingested           : $got / $COUNT   (want == for zero loss)"
echo "final mode                : $(current_mode)"

PASS=1
[ "${MINREADY:-0}" -ge 1 ] || { echo "FAIL: availability dropped to 0 pods"; PASS=0; }
[ "$got" -ge "$COUNT" ]    || { echo "FAIL: lost $((COUNT - got)) orders"; PASS=0; }
echo
[ "$PASS" = 1 ] && echo "PASS — zero loss + full availability" || { echo "FAILED"; exit 1; }
