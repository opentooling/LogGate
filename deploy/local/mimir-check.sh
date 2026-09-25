#!/usr/bin/env bash
# Proves LogGate's pod listing and its Grafana dashboard work against Grafana
# Mimir, not only Prometheus. Mimir differs where it matters: its Prometheus
# API sits under /prometheus, it requires a tenant (X-Scope-OrgID) with
# multi-tenancy on, which is its default, and it answers series queries from
# its own index. This runs a single-binary Mimir, has the local Prometheus
# remote-write to it under a tenant, points the deployed LogGate at it, and
# checks the listing and every dashboard query. Everything is put back
# afterwards unless KEEP=1.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT/e2e"
# shellcheck source=e2e/lib.sh
source ./lib.sh

CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
NAMESPACE="${APP_NS:-loggate}"
OBS_NS="${OBS_NS:-observability}"
MIMIR_IMAGE="${MIMIR_IMAGE:-grafana/mimir:3.2.1}"
TENANT="loggate"
MIMIR="http://mimir.$OBS_NS.svc.cluster.local:8080"
k() { kubectl --context "$CONTEXT" "$@"; }

echo
echo "LogGate against Grafana Mimir ($MIMIR_IMAGE)"
echo

k apply -n "$OBS_NS" -f - >/dev/null <<YAML
apiVersion: v1
kind: ConfigMap
metadata:
  name: mimir
data:
  mimir.yaml: |
    multitenancy_enabled: true
    target: all
    server:
      http_listen_port: 8080
      grpc_listen_port: 9095
      log_level: warn
    common:
      storage:
        backend: filesystem
        filesystem:
          dir: /data/common
    blocks_storage:
      storage_prefix: blocks
      tsdb:
        dir: /data/tsdb
    ingester:
      ring:
        replication_factor: 1
        kvstore:
          store: inmemory
    distributor:
      ring:
        kvstore:
          store: inmemory
    store_gateway:
      sharding_ring:
        replication_factor: 1
        kvstore:
          store: inmemory
    compactor:
      data_dir: /data/compactor
      sharding_ring:
        kvstore:
          store: inmemory
    ruler_storage:
      backend: filesystem
      filesystem:
        dir: /data/rules
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mimir
spec:
  replicas: 1
  selector:
    matchLabels: {app: mimir}
  template:
    metadata:
      labels: {app: mimir}
    spec:
      containers:
        - name: mimir
          image: $MIMIR_IMAGE
          args: ["-config.file=/etc/mimir/mimir.yaml"]
          ports:
            - {name: http, containerPort: 8080}
          readinessProbe:
            httpGet: {path: /ready, port: http}
            periodSeconds: 5
          resources:
            requests: {cpu: 50m, memory: 256Mi}
            limits: {memory: 1Gi}
          volumeMounts:
            - {name: config, mountPath: /etc/mimir}
            - {name: data, mountPath: /data}
      volumes:
        - name: config
          configMap: {name: mimir}
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: mimir
spec:
  selector: {app: mimir}
  ports:
    - {name: http, port: 8080, targetPort: http}
YAML
if k rollout status deploy/mimir -n "$OBS_NS" --timeout=300s >/dev/null 2>&1; then
  ok "Mimir is running, multi-tenant"
else
  bad "Mimir did not become ready"
  k logs -n "$OBS_NS" deploy/mimir --tail=20
  summary; exit 1
fi

ORIGINAL_URL="$(k get deploy loggate -n "$NAMESPACE" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PODS_METRICS_URL")].value}')"
ORIGINAL_TENANT="$(k get deploy loggate -n "$NAMESPACE" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PODS_TENANT_ID")].value}')"
set_env() {
  k set env deploy/loggate -n "$NAMESPACE" "$@" >/dev/null 2>&1
  k rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1
}
restore() {
  set_env "PODS_METRICS_URL=$ORIGINAL_URL" "PODS_TENANT_ID=$ORIGINAL_TENANT"
  if [[ "${KEEP:-0}" != "1" ]]; then
    helm --kube-context "$CONTEXT" upgrade prometheus prometheus \
      --repo https://prometheus-community.github.io/helm-charts -n "$OBS_NS" \
      --reuse-values --set 'server.remoteWrite=null' >/dev/null 2>&1
    k delete -n "$OBS_NS" deploy/mimir svc/mimir configmap/mimir >/dev/null 2>&1
  fi
}
trap restore EXIT

# The same metrics Prometheus holds, written to Mimir under one tenant.
helm --kube-context "$CONTEXT" upgrade prometheus prometheus \
  --repo https://prometheus-community.github.io/helm-charts -n "$OBS_NS" --reuse-values \
  --set "server.remoteWrite[0].url=$MIMIR/api/v1/push" \
  --set "server.remoteWrite[0].headers.X-Scope-OrgID=$TENANT" \
  --wait --timeout 5m >/dev/null 2>&1 \
  && ok "Prometheus remote-writes to Mimir as tenant $TENANT" \
  || bad "Prometheus could not be pointed at Mimir"

# Asked from inside the cluster with the application's own image, which has
# wget, so the tenant header can be sent.
mimir_get() { # mimir_get <path and query> [tenant]
  k exec -n "$NAMESPACE" deploy/loggate -- wget -q -O - -T 10 \
    ${2:+--header "X-Scope-OrgID: $2"} "$MIMIR/prometheus$1" 2>/dev/null
}
series=0
for _ in $(seq 1 36); do
  series="$(mimir_get "/api/v1/query?query=count(kube_pod_info%7Bnamespace%3D%22platform-dev%22%7D)" "$TENANT" \
    | jq_get 'd["data"]["result"][0]["value"][1] if d["data"]["result"] else 0')"
  [[ "${series:-0}" -ge 1 ]] && break
  sleep 5
done
check_at_least "Mimir holds kube_pod_info for platform-dev" 1 "${series:-0}"

set_env "PODS_METRICS_URL=$MIMIR/prometheus" "PODS_TENANT_ID=$TENANT"
FROM="$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '1 hour ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
JAR="$(mktemp)"
login alice "$JAR" >/dev/null
check "LogGate, pointed at Mimir's /prometheus API, offers pods" "True" \
  "$(body "$(api "$JAR" GET /api/me)" | jq_get 'd["podsListable"]')"
r="$(api "$JAR" GET "/api/pods?namespace=platform-dev&from=$FROM&to=$TO")"
check "the series API answers through Mimir, limit and all" "200" "$(status "$r")"
check "listing the seeded API pods" "yes" \
  "$(body "$r" | jq_get '"yes" if any(p["name"].startswith("platform-api-") for p in d["pods"]) else "no"')"
check "and only platform-dev's, matched with the cluster label" "platform-dev" \
  "$(body "$r" | jq_get '",".join(sorted({p["namespace"] for p in d["pods"]}))')"
POD="$(body "$r" | jq_get 'next(p["name"] for p in d["pods"] if p["name"].startswith("platform-api-"))')"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"pods\":[\"$POD\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "and a pod picked from Mimir's list narrows the export" "200" "$(status "$r")"

set_env "PODS_METRICS_URL=$MIMIR/prometheus" "PODS_TENANT_ID="
login alice "$JAR" >/dev/null
r="$(api "$JAR" GET "/api/pods?namespace=platform-dev&from=$FROM&to=$TO")"
check "without the tenant Mimir refuses, and LogGate says pods cannot be listed" "503" "$(status "$r")"

# Every panel of the Grafana dashboard, asked of Mimir rather than Prometheus.
panel_queries="$(python3 - "$REPO_ROOT/deploy/helm/loggate/dashboards/loggate.json" <<'PY'
import json, sys, urllib.parse
dashboard = json.load(open(sys.argv[1]))
for panel in dashboard["panels"]:
    for target in panel.get("targets", []):
        expr = target["expr"].replace("$namespace", ".*").replace("$__rate_interval", "2m")
        print(panel["title"] + "\t" + urllib.parse.quote(expr))
PY
)"
failed=""
while IFS=$'\t' read -r title expr; do
  [[ -z "$title" ]] && continue
  result="$(mimir_get "/api/v1/query?query=$expr" "$TENANT" | jq_get 'd["status"]')"
  [[ "$result" == "success" ]] || failed="$failed${failed:+, }$title"
done <<< "$panel_queries"
check "every dashboard query runs on Mimir" "none" "${failed:-none}"
has_data="$(mimir_get "/api/v1/query?query=count(loggate_jobs_active)" "$TENANT" \
  | jq_get 'd["data"]["result"][0]["value"][1] if d["data"]["result"] else 0')"
check_at_least "with LogGate's own metrics in it" 1 "${has_data:-0}"

summary
