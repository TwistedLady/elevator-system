#!/usr/bin/env bash
# Tear down the Spark BI jobs. Removes the driver Deployments (executor pods are owned by the driver
# pods and are GC'd with them), plus config / netpol / RBAC. Leaves the BI tables and the persisted
# checkpoint/logs on the node intact.
#   --purge  also delete leftover executor pods and drop the BI tables.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> deleting driver Deployments + config + netpol + rbac"
kubectl delete -f k8s/bi/mileage-driver.yaml --ignore-not-found
kubectl delete -f k8s/bi/orders-served-driver.yaml --ignore-not-found
kubectl delete -f k8s/bi/network-policies.yaml --ignore-not-found
kubectl delete -f k8s/bi/configmap.yaml --ignore-not-found
kubectl delete -f k8s/bi/rbac.yaml --ignore-not-found

# Executors are usually GC'd via their ownerReference; sweep any stragglers.
kubectl delete pods -l app=elevator-bi,role=executor --ignore-not-found

if [ "${1:-}" = "--purge" ]; then
  echo "==> --purge: dropping BI tables"
  kubectl exec -i postgres-0 -- psql -U elevator -d elevator \
    -c 'DROP TABLE IF EXISTS elevator_mileage, elevator_orders_served;' || true
fi

echo "==> done"
