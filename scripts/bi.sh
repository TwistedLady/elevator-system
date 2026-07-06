#!/usr/bin/env bash
# Turn the BI (Spark analytics) layer on or off.
#
# ELEVATOR_BI_ENABLED in the elevator-config ConfigMap is the single source of truth; the api
# hot-reloads it (no restart): when off, GET /api/mileage & /api/served return 503, /actuator/health
# shows the `bi` component DISABLED (overall stays UP), and both consoles hide the Stats tab.
#
# A ConfigMap flag can't create or delete pods, so this script also reconciles the cluster:
#   on  -> set the flag true,  apply  postgres-stats + the Spark BI drivers
#   off -> set the flag false, delete postgres-stats + the Spark BI drivers (kills the pods)
#
#   Usage: scripts/bi.sh on|off
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Spark drivers first so their pods stop before their DB; DB manifests applied first when turning on.
BI_MANIFESTS=(k8s/bi/rbac.yaml k8s/bi/network-policies.yaml k8s/bi/configmap.yaml
              k8s/bi/mileage-driver.yaml k8s/bi/orders-served-driver.yaml)
STATS_MANIFESTS=(k8s/postgres-stats-init.yaml k8s/postgres-stats.yaml)

f_args() { local m; for m in "$@"; do printf ' -f %s' "$m"; done; }

case "${1:-}" in
  on)
    kubectl patch configmap elevator-config --type=merge -p '{"data":{"ELEVATOR_BI_ENABLED":"true"}}'
    kubectl apply $(f_args "${STATS_MANIFESTS[@]}")
    kubectl apply $(f_args "${BI_MANIFESTS[@]}")
    echo "BI on: flag=true, postgres-stats + Spark drivers applied. Stats tab returns within ~5s."
    ;;
  off)
    kubectl patch configmap elevator-config --type=merge -p '{"data":{"ELEVATOR_BI_ENABLED":"false"}}'
    kubectl delete --ignore-not-found $(f_args "${BI_MANIFESTS[@]}")
    kubectl delete --ignore-not-found $(f_args "${STATS_MANIFESTS[@]}")
    echo "BI off: flag=false, Spark drivers + postgres-stats deleted (pods gone)."
    echo "        api hot-reloads: mileage/served -> 503, health bi=DISABLED, consoles hide Stats."
    ;;
  *)
    echo "usage: scripts/bi.sh on|off"
    exit 1
    ;;
esac
