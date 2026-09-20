#!/usr/bin/env bash
# Generates log volume fast, for exercising an export at a realistic size.
#
# The ordinary seed-logs.sh workloads emit a few lines a second, which is
# realistic but would take days to produce gigabytes. This one uses awk to emit
# batches, which is fast enough to reach a gigabyte in minutes while still
# travelling the real path: container stdout, Alloy, Loki.
#
#   deploy/local/seed-volume.sh            # start generating
#   deploy/local/seed-volume.sh --remove   # stop
set -euo pipefail

NAMESPACE="${NAMESPACE:-platform-dev}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
REPLICAS="${REPLICAS:-4}"
# Lines per second per pod. About 190 bytes each.
RATE="${RATE:-3000}"

if [[ "${1:-}" == "--remove" ]]; then
  kubectl --context "$CONTEXT" delete deployment platform-bulk -n "$NAMESPACE" --ignore-not-found
  exit 0
fi

kubectl --context "$CONTEXT" apply -n "$NAMESPACE" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: platform-bulk
spec:
  replicas: ${REPLICAS}
  selector:
    matchLabels:
      app.kubernetes.io/name: platform-bulk
  template:
    metadata:
      labels:
        app.kubernetes.io/name: platform-bulk
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: bulk
          image: busybox:1.37
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          command: ["/bin/sh", "-c"]
          args:
            - |
              # awk emits a whole second's worth per invocation, so there is no
              # per-line process spawn and the rate is actually achievable.
              n=0
              while true; do
                awk -v start=\$n -v count=${RATE} -v host="\${HOSTNAME}" 'BEGIN {
                  for (i = 0; i < count; i++) {
                    seq = start + i
                    printf "{\"level\":\"info\",\"team\":\"platform\",\"pod\":\"%s\",\"seq\":%d,\"msg\":\"bulk request handled\",\"path\":\"/api/v1/things/%d\",\"trace\":\"%08x%08x\",\"duration_ms\":%d}\n", host, seq, seq, seq * 2654435761, seq * 40503, seq % 250
                  }
                }'
                n=\$((n + ${RATE}))
                sleep 1
              done
          resources:
            requests:
              cpu: 100m
              memory: 32Mi
            limits:
              memory: 128Mi
EOF

echo "Generating about $((RATE * REPLICAS * 190 / 1024 / 1024)) MB/s of logs in $NAMESPACE."
echo "Stop with: deploy/local/seed-volume.sh --remove"
