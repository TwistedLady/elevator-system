#!/usr/bin/env bash
# Recreate the kind cluster 'elevator' with Calico as the CNI (so NetworkPolicy is ENFORCED),
# redeploy the whole system, apply the network policies, and seed.
#
# WARNING: this DELETES the cluster — Postgres data (journal/read-model) and Kafka are wiped.
#
# Env: CALICO_VER (default v3.28.2), SEED (default 200).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CALICO_VER="${CALICO_VER:-v3.28.2}"
SEED="${SEED:-200}"

echo "==> [1/8] delete existing cluster 'elevator'"
kind delete cluster --name elevator || true

echo "==> [2/8] create cluster with Calico CNI (kindnet disabled)"
kind create cluster --config scripts/kind-calico.yaml

echo "==> [3/8] install Calico ${CALICO_VER} + wait ready"
kubectl apply --server-side --force-conflicts -f "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VER}/manifests/calico.yaml"
kubectl -n kube-system rollout status daemonset/calico-node --timeout=240s

echo "==> [4/8] load local app/api images (kafka/postgres are public → pulled)"
kind load docker-image elevator-api:local elevator-app:local --name elevator

echo "==> [5/8] dummy ghcr-pull secret (images are local/public; satisfies manifest ref)"
kubectl create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io --docker-username=none --docker-password=none \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> [6/8] deploy manifests"
kubectl apply -f k8s/rbac.yaml -f k8s/configmap.yaml -f k8s/postgres-init.yaml \
  -f k8s/postgres.yaml -f k8s/kafka.yaml -f k8s/app.yaml -f k8s/api.yaml
kubectl rollout status statefulset/postgres --timeout=180s
kubectl rollout status deployment/kafka --timeout=180s
kubectl rollout status deployment/elevator-api --timeout=180s
kubectl rollout status deployment/elevator-app --timeout=300s

echo "==> [7/8] apply network policies (Calico enforces them now)"
kubectl apply -f k8s/network-policies.yaml

echo "==> [8/8] seed ${SEED} orders"
scripts/seed-k8s.sh "${SEED}" || echo "   (seed failed — re-run scripts/seed-k8s.sh)"

echo "==> done"; kubectl get pods
