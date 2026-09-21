#!/usr/bin/env bash
# Pushes a second cluster's logs into the local Loki.
#
# Stands in for a cluster that ships to the same Loki but that LogGate has no
# network path to: its namespaces exist only as labels in Loki, never in any
# Kubernetes API LogGate can read. That is the case open access mode is for.
#
# It deliberately includes a namespace with the same name as a local one
# (platform-dev), which is what team-label mode's pin to its own cluster
# protects against.
#
#   deploy/local/seed-remote-cluster.sh            # 600 lines per stream
#   LINES=5000 deploy/local/seed-remote-cluster.sh
set -euo pipefail

CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
OBS_NS="${OBS_NS:-observability}"
CLUSTER="${REMOTE_CLUSTER:-edge-eu}"
LINES="${LINES:-600}"
# Spread over the last ten minutes, so "the last hour" always covers them.
SPREAD_SECONDS="${SPREAD_SECONDS:-600}"

payload="$(mktemp)"
trap 'rm -f "$payload"' EXIT

python3 - "$CLUSTER" "$LINES" "$SPREAD_SECONDS" > "$payload" <<'PY'
import json, sys, time
cluster, lines, spread = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
now = time.time_ns()
step = spread * 1_000_000_000 // max(lines, 1)
def stream(namespace, app, message):
    labels = {"cluster": cluster, "namespace": namespace, "app": app,
              "pod": f"{app}-7f9c4d-x2x8q", "container": "api"}
    values = [[str(now - (lines - i) * step),
               json.dumps({"level": "info", "cluster": cluster, "seq": i, "msg": message})]
              for i in range(lines)]
    return {"stream": labels, "values": values}
print(json.dumps({"streams": [
    stream("checkout-prod", "checkout-api", "order placed"),
    stream("platform-dev", "platform-api", f"request handled in {cluster}"),
]}))
PY

# Posted from inside the cluster by a throwaway curl pod: Loki needs a
# Content-Type to tell JSON from protobuf, and kubectl's raw POST sends none.
kubectl --context "$CONTEXT" run "seed-remote-$RANDOM" -n "$OBS_NS" --rm -i --restart=Never \
  --quiet --image=curlimages/curl:8.16.0 -- \
  curl -sS --fail-with-body -X POST -H 'Content-Type: application/json' \
  --data-binary @- "http://loki-gateway.$OBS_NS.svc.cluster.local/loki/api/v1/push" < "$payload"
echo "pushed $LINES lines each for checkout-prod and platform-dev in cluster $CLUSTER"
