#!/usr/bin/env bash
# Build LogGate, stand up a local k3d cluster with a full Loki stack, and
# install the chart. Idempotent: safe to re-run after a code change.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER="${CLUSTER:-loggate}"
APP_NS="${APP_NS:-loggate}"
OBS_NS="${OBS_NS:-observability}"
IMAGE="${IMAGE:-loggate}"
TAG="${TAG:-$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || date +%s)}"

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# Host port for the cluster's ingress. Not 80 (another k3d cluster may hold it)
# and not 8080 (a common default for port-forwards and local servers).
HOST_PORT="${HOST_PORT:-8088}"

# --- cluster ----------------------------------------------------------------
if ! k3d cluster list --output json | grep -q "\"name\":\"$CLUSTER\""; then
  log "Creating k3d cluster '$CLUSTER'"
  k3d cluster create "$CLUSTER" \
    --agents 1 \
    --port "${HOST_PORT}:80@loadbalancer" \
    --k3s-arg "--disable=traefik@server:0" \
    --wait
else
  log "Reusing existing k3d cluster '$CLUSTER'"
fi

kubectl config use-context "k3d-$CLUSTER" >/dev/null

# Installed on every run, not only at cluster creation: if a previous run died
# midway, the admission webhook can be left with an empty caBundle (its patch
# Job never ran), which fails every later Ingress apply. Re-running the install
# re-runs the hooks and repairs it.
log "Installing ingress-nginx"
helm upgrade --install ingress-nginx ingress-nginx \
  --repo https://kubernetes.github.io/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --set controller.service.type=LoadBalancer \
  --set controller.hostPort.enabled=false \
  --set controller.ingressClassResource.default=true \
  --set controller.watchIngressWithoutClass=true \
  --wait --timeout 5m

# --- observability stack ----------------------------------------------------
STACK="$REPO_ROOT/deploy/local/stack"

if [[ "${SKIP_STACK:-0}" != "1" ]]; then
  log "Installing Loki"
  helm upgrade --install loki loki \
    --repo https://grafana.github.io/helm-charts \
    --namespace "$OBS_NS" --create-namespace \
    -f "$STACK/loki-values.yaml" \
    --wait --timeout 10m

  log "Installing Alloy (pod log collection)"
  helm upgrade --install alloy alloy \
    --repo https://grafana.github.io/helm-charts \
    --namespace "$OBS_NS" \
    -f "$STACK/alloy-values.yaml" \
    --wait --timeout 5m

  log "Installing Grafana"
  helm upgrade --install grafana grafana \
    --repo https://grafana.github.io/helm-charts \
    --namespace "$OBS_NS" \
    -f "$STACK/grafana-values.yaml" \
    --wait --timeout 5m

  log "Installing MinIO (export artifact storage)"
  helm upgrade --install minio minio \
    --repo https://charts.min.io/ \
    --namespace "$OBS_NS" \
    -f "$STACK/minio-values.yaml" \
    --wait --timeout 5m
else
  log "SKIP_STACK=1 — leaving the observability stack alone"
fi

# --- build ------------------------------------------------------------------
log "Building and testing the backend"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" check

# Jib builds the image from the compiled classes with no container runtime and
# no Dockerfile, straight to a tarball that k3d can import.
log "Building image $IMAGE:$TAG with Jib"
# Jib is not configuration-cache compatible yet, so disable it for this task
# only rather than giving it up for every other build.
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :backend:jibBuildTar \
  --no-configuration-cache \
  -PimageName="$IMAGE" -PimageTag="$TAG"

log "Importing image into k3d"
k3d image import "$REPO_ROOT/backend/build/jib-image.tar" -c "$CLUSTER"

# --- install ----------------------------------------------------------------
log "Installing the LogGate chart"
helm upgrade --install loggate "$REPO_ROOT/deploy/helm/loggate" \
  --namespace "$APP_NS" --create-namespace \
  -f "$REPO_ROOT/deploy/local/values-local.yaml" \
  --set-string "image.tag=$TAG" \
  --wait --timeout 5m

log "Running chart tests"
helm test loggate --namespace "$APP_NS"

cat <<EOF

LogGate    http://loggate.localtest.me:${HOST_PORT}
Grafana    http://grafana.localtest.me:${HOST_PORT}      (admin / loggate)
MinIO      http://minio.localtest.me:${HOST_PORT}        (loggate / loggate-local-dev)

Seed a log-producing workload:
  deploy/local/seed-logs.sh
EOF
