#!/usr/bin/env bash
# TLS for elevator-api: a small CA that signs a server leaf cert (rustls rejects a self-signed CA
# used directly as the server cert). Creates the k8s secret (elevator-api-tls) the api mounts, and
# refreshes the CA cert the console bundles to trust. Private keys / keystore are NOT committed.
#   Usage: scripts/gen-tls.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${TLS_DIR:-/tmp/elevator-tls}"; mkdir -p "$DIR"
PASS="${TLS_PASSWORD:-changeit}"

# 1) CA (self-signed, CA:TRUE)
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout "$DIR/ca.key" -out "$DIR/ca.crt" -subj "/CN=elevator-ca"

# 2) server key + CSR
openssl req -newkey rsa:2048 -nodes -keyout "$DIR/server.key" -out "$DIR/server.csr" -subj "/CN=elevator-api"

# 3) sign the server leaf with the CA (SANs, CA:FALSE, serverAuth EKU — required by rustls-webpki)
openssl x509 -req -in "$DIR/server.csr" -CA "$DIR/ca.crt" -CAkey "$DIR/ca.key" -CAcreateserial \
  -days 3650 -out "$DIR/server.crt" -extfile <(printf '%s\n' \
    "subjectAltName=DNS:localhost,DNS:elevator-api,DNS:elevator-api.default.svc.cluster.local,IP:127.0.0.1" \
    "basicConstraints=CA:FALSE" \
    "extendedKeyUsage=serverAuth")

# 4) keystore = server leaf + key, with the CA in the chain
openssl pkcs12 -export -in "$DIR/server.crt" -inkey "$DIR/server.key" -certfile "$DIR/ca.crt" \
  -out "$DIR/keystore.p12" -name elevator-api -passout "pass:$PASS"

# 5) console trusts the CA
cp "$DIR/ca.crt" "$ROOT/cli-console/certs/elevator-ca.crt"
kubectl create secret generic elevator-api-tls \
  --from-file=keystore.p12="$DIR/keystore.p12" --from-literal=password="$PASS" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "==> elevator-api-tls secret applied; console CA at cli-console/certs/elevator-ca.crt"
