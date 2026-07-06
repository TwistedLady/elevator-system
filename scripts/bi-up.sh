#!/usr/bin/env bash
# Build + deploy the Spark mileage BI job onto the running kind cluster 'elevator'.
#   1. build the uber jar (Scala 2.12)   2. build + kind-load the image
#   3. apply RBAC / config / netpol / the driver Deployment (which spawns executor pods)
#
# Env: SKIP_TESTS=1 to skip the unit tests during the jar build.
# Prereqs: the core system is already up (scripts/kind-calico-up.sh); docker, kind, kubectl on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MVN_ARGS=(-B -ntp -f elevator-bi/pom.xml clean package)
[ "${SKIP_TESTS:-0}" = "1" ] && MVN_ARGS+=(-DskipTests)

echo "==> [1/5] build uber jar"
mvn "${MVN_ARGS[@]}"

echo "==> [2/5] build image elevator-bi:local"
docker build -t elevator-bi:local elevator-bi

echo "==> [3/5] load image into kind cluster 'elevator'"
kind load docker-image elevator-bi:local --name elevator

echo "==> [4/5] apply manifests (rbac, config, network policies, drivers)"
kubectl apply \
  -f k8s/bi/rbac.yaml \
  -f k8s/bi/configmap.yaml \
  -f k8s/bi/network-policies.yaml \
  -f k8s/bi/mileage-driver.yaml \
  -f k8s/bi/orders-served-driver.yaml

echo "==> [5/5] wait for the drivers, then their executors"
kubectl rollout status deployment/elevator-mileage --timeout=180s
kubectl rollout status deployment/elevator-orders-served --timeout=180s
# Executors appear a few seconds after the driver's Spark context starts.
for i in $(seq 1 30); do
  n=$(kubectl get pods -l app=elevator-bi,role=executor --no-headers 2>/dev/null | grep -c Running || true)
  [ "${n:-0}" -ge 1 ] && break
  sleep 2
done

echo
kubectl get pods -l app=elevator-bi -o wide
echo
echo "Mileage logs:  kubectl logs -f deploy/elevator-mileage"
echo "Served logs:   kubectl logs -f deploy/elevator-orders-served"
echo "Mileage table: kubectl exec -it postgres-0 -- psql -U elevator -d elevator -c 'SELECT * FROM elevator_mileage ORDER BY floors_travelled DESC;'"
echo "Served table:  kubectl exec -it postgres-0 -- psql -U elevator -d elevator -c 'SELECT * FROM elevator_orders_served ORDER BY orders_served DESC;'"
echo "Save all logs: scripts/bi-logs.sh"
