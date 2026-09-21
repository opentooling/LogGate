#!/usr/bin/env bash
# Waits until Loki actually holds logs for a namespace.
#
# The export suites need real data in Loki, and there is a pipeline between a
# pod writing a line and Loki being able to return it: the container runtime,
# Alloy tailing the file, Loki ingesting it. Sleeping for a guessed interval
# would be flaky on a slow runner and wasteful on a fast one, so this asks Loki
# the question the tests depend on, through the Kubernetes API server's service
# proxy so no port-forward is needed.
set -uo pipefail

CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
OBS_NS="${OBS_NS:-observability}"
NAMESPACE="${1:-platform-dev}"
MIN_LINES="${MIN_LINES:-1000}"
DEADLINE=$((SECONDS + ${TIMEOUT:-300}))

query="sum(count_over_time({namespace=\"$NAMESPACE\"}[10m]))"
encoded="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$query")"
path="/api/v1/namespaces/$OBS_NS/services/loki-gateway:80/proxy/loki/api/v1/query?query=$encoded"

while (( SECONDS < DEADLINE )); do
  lines="$(kubectl --context "$CONTEXT" get --raw "$path" 2>/dev/null | python3 -c '
import json, sys
result = json.load(sys.stdin)["data"]["result"]
print(int(float(result[0]["value"][1])) if result else 0)
' 2>/dev/null)"
  lines="${lines:-0}"
  echo "  $NAMESPACE: $lines lines in Loki (want $MIN_LINES)"
  (( lines >= MIN_LINES )) && exit 0
  sleep 10
done

echo "Timed out waiting for $MIN_LINES lines from $NAMESPACE in Loki" >&2
exit 1
