#!/usr/bin/env bash
# Tear down the Spark mileage BI job. Removes the driver Deployment (executor pods are owned by the
# driver pod and are GC'd with it), plus config / netpol / RBAC. Leaves the elevator_mileage table
# and the persisted checkpoint/logs on the node intact.
#   --purge  also delete leftover executor pods and the elevator_mileage table.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> deleting driver Deployment + config + netpol + rbac"
kubectl delete -f k8s/bi/mileage-driver.yaml --ignore-not-found
kubectl delete -f k8s/bi/network-policies.yaml --ignore-not-found
kubectl delete -f k8s/bi/configmap.yaml --ignore-not-found
kubectl delete -f k8s/bi/rbac.yaml --ignore-not-found

# Executors are usually GC'd via their ownerReference; sweep any stragglers.
kubectl delete pods -l app=elevator-bi,role=executor --ignore-not-found

if [ "${1:-}" = "--purge" ]; then
  echo "==> --purge: dropping elevator_mileage table"
  kubectl exec -i postgres-0 -- psql -U elevator -d elevator \
    -c 'DROP TABLE IF EXISTS elevator_mileage;' || true
fi

echo "==> done"
