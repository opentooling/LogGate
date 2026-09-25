#!/usr/bin/env bash
# Build LogGate, stand up a local k3d cluster with a full Loki stack, and
# install the chart. Idempotent: safe to re-run after a code change.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLUSTER="${CLUSTER:-loggate}"
APP_NS="${APP_NS:-loggate}"
OBS_NS="${OBS_NS:-observability}"
IMAGE="${IMAGE:-loggate}"
# A dirty tree gets a unique tag. Otherwise an uncommitted change rebuilds the
# image under the same tag, Helm sees an unchanged pod spec, and the running
# pods keep the old code - which looks exactly like the change not working.
if [[ -z "${TAG:-}" ]]; then
  TAG="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo untracked)"
  if ! git -C "$REPO_ROOT" diff --quiet HEAD 2>/dev/null; then
    TAG="${TAG}-dirty-$(date +%H%M%S)"
  fi
fi

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }

# Host port for the cluster's ingress. Not 80 (another k3d cluster may hold it)
# and not 8080 (a common default for port-forwards and local servers).
HOST_PORT="${HOST_PORT:-8088}"

# --- cluster ----------------------------------------------------------------
if ! k3d cluster list --output json | grep -q "\"name\":\"$CLUSTER\""; then
  log "Creating k3d cluster '$CLUSTER'"
  # Podman occasionally cannot serve a just-started container's journald logs
  # ("unable to open a handle to the library"), which k3d reads to detect that
  # k3s is up. It is a startup race, not a misconfiguration, so retry rather
  # than fail the whole deploy.
  created=0
  for attempt in 1 2 3; do
    if k3d cluster create "$CLUSTER" \
      --agents 1 \
      --port "${HOST_PORT}:${HOST_PORT}@loadbalancer" \
      --k3s-arg "--disable=traefik@server:0" \
      --wait; then
      created=1
      break
    fi
    log "Cluster creation attempt $attempt failed; cleaning up and retrying"
    k3d cluster delete "$CLUSTER" >/dev/null 2>&1 || true
    sleep 5
  done
  if [[ "$created" != "1" ]]; then
    echo "Cluster creation failed after 3 attempts" >&2
    exit 1
  fi
else
  log "Reusing existing k3d cluster '$CLUSTER'"
fi

kubectl config use-context "k3d-$CLUSTER" >/dev/null

# Installed on every run, not only at cluster creation: if a previous run died
# midway, the admission webhook can be left with an empty caBundle (its patch
# Job never ran), which fails every later Ingress apply. Re-running the install
# re-runs the hooks and repairs it.
# The ingress listens on the same port the browser uses, so X-Forwarded-Port
# matches the public URL. Without that the OAuth2 callback URL the application
# reconstructs does not match the registered redirect_uri and login fails.
log "Installing ingress-nginx"
helm upgrade --install ingress-nginx ingress-nginx \
  --repo https://kubernetes.github.io/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --set controller.service.type=LoadBalancer \
  --set controller.service.ports.http=${HOST_PORT} \
  --set controller.containerPort.http=${HOST_PORT} \
  --set controller.extraArgs.http-port=${HOST_PORT} \
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

  log "Installing Prometheus (LogGate metrics, and kube_pod_info for pod listing)"
  helm upgrade --install prometheus prometheus \
    --repo https://prometheus-community.github.io/helm-charts \
    --namespace "$OBS_NS" \
    -f "$STACK/prometheus-values.yaml" \
    --wait --timeout 10m

  log "Installing Grafana"
  helm upgrade --install grafana grafana \
    --repo https://grafana.github.io/helm-charts \
    --namespace "$OBS_NS" \
    -f "$STACK/grafana-values.yaml" \
    --wait --timeout 5m

  # MinIO no longer publishes pullable images; a stack from before the switch
  # still has its release, which is removed rather than left beside the new one.
  if helm status minio -n "$OBS_NS" >/dev/null 2>&1; then
    log "Removing the old MinIO release"
    helm uninstall minio -n "$OBS_NS" --wait >/dev/null
  fi

  log "Installing the object store (Versity S3 Gateway)"
  kubectl apply -n "$OBS_NS" -f "$STACK/object-store.yaml" >/dev/null
  kubectl rollout status deploy/s3 -n "$OBS_NS" --timeout=5m

  # The bucket, created through the S3 API itself so nothing else has to be
  # pulled to do it. Retried while the ingress picks up the new host.
  S3_KEY="$(kubectl get secret s3-root -n "$OBS_NS" -o jsonpath='{.data.access-key}' | base64 --decode)"
  S3_SECRET="$(kubectl get secret s3-root -n "$OBS_NS" -o jsonpath='{.data.secret-key}' | base64 --decode)"
  bucket=""
  for _ in $(seq 1 30); do
    bucket="$(curl -s -o /dev/null -w '%{http_code}' --aws-sigv4 "aws:amz:us-east-1:s3" \
      --user "$S3_KEY:$S3_SECRET" -X PUT "http://s3.localtest.me:${HOST_PORT}/loggate-exports")"
    # 200 when created; 409 when it already exists, which is as good.
    [[ "$bucket" == "200" || "$bucket" == "409" ]] && break
    sleep 2
  done
  if [[ "$bucket" != "200" && "$bucket" != "409" ]]; then
    echo "Could not create the export bucket (last answer: HTTP $bucket)" >&2
    exit 1
  fi
else
  log "SKIP_STACK=1 — leaving the observability stack alone"
fi

# --- build ------------------------------------------------------------------
# CI runs the checks in a job of their own and deploys only once they pass, so
# it skips them here rather than paying for the whole suite twice.
if [[ "${SKIP_CHECKS:-0}" != "1" ]]; then
  log "Building and testing the backend"
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" check
else
  log "SKIP_CHECKS=1 — building without running the test suite"
fi

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
# The OIDC issuer URL must be byte-identical for the browser and for the
# application, or the id_token's `iss` will not match. The browser reaches it
# through the host port; the application reaches the same name and port by
# resolving it to the ingress controller inside the cluster.
INGRESS_IP="$(kubectl -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.spec.clusterIP}')"
log "Resolving auth.localtest.me to the ingress at $INGRESS_IP for in-cluster calls"

log "Installing the LogGate chart"
helm upgrade --install loggate "$REPO_ROOT/deploy/helm/loggate" \
  --namespace "$APP_NS" --create-namespace \
  -f "$REPO_ROOT/deploy/local/values-local.yaml" \
  ${EXTRA_VALUES:+-f "$EXTRA_VALUES"} \
  --set-string "image.tag=$TAG" \
  --set-string "hostAliases[0].ip=$INGRESS_IP" \
  --set-string "hostAliases[0].hostnames[0]=auth.localtest.me" \
  --wait --timeout 5m

log "Running chart tests"
helm test loggate --namespace "$APP_NS"

cat <<EOF

LogGate    http://loggate.localtest.me:${HOST_PORT}
Keycloak   http://auth.localtest.me:${HOST_PORT}        (admin / admin)
Grafana    http://grafana.localtest.me:${HOST_PORT}      (admin / loggate; dashboard "LogGate")
Prometheus http://prometheus.localtest.me:${HOST_PORT}
S3         http://s3.localtest.me:${HOST_PORT}           (S3 API; loggate / loggate-local-dev)

Seed a log-producing workload:
  deploy/local/seed-logs.sh
EOF
