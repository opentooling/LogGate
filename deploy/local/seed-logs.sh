#!/usr/bin/env bash
# Create labelled team namespaces with chatty workloads, so there is realistic
# volume in Loki and real label fixtures for the authorization model.
#
#   xyz.com/team: platform   ->  group ad-platform-dev
#   xyz.com/team: payments   ->  group ad-payments-dev
set -euo pipefail

TEAM_LABEL="${TEAM_LABEL:-xyz.com/team}"
# Lines per second per pod. Raise it to build up GBs for a realistic export.
RATE="${RATE:-20}"
REPLICAS="${REPLICAS:-2}"

teams=("platform" "payments")

if [[ "${1:-}" == "--remove" ]]; then
  for team in "${teams[@]}"; do
    kubectl delete namespace "${team}-dev" --ignore-not-found
  done
  exit 0
fi

for team in "${teams[@]}"; do
  ns="${team}-dev"
  kubectl create namespace "$ns" --dry-run=client -o yaml | kubectl apply -f -
  kubectl label namespace "$ns" "${TEAM_LABEL}=${team}" --overwrite

  kubectl apply -n "$ns" -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${team}-api
spec:
  replicas: ${REPLICAS}
  selector:
    matchLabels:
      app.kubernetes.io/name: ${team}-api
  template:
    metadata:
      labels:
        app.kubernetes.io/name: ${team}-api
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: api
          image: busybox:1.37
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          command: ["/bin/sh", "-c"]
          args:
            - |
              n=0
              while true; do
                i=0
                while [ \$i -lt ${RATE} ]; do
                  n=\$((n+1)); i=\$((i+1))
                  echo "{\"ts\":\"\$(date -Iseconds)\",\"level\":\"info\",\"team\":\"${team}\",\"pod\":\"\${HOSTNAME}\",\"seq\":\${n},\"msg\":\"request handled\",\"path\":\"/api/v1/things/\${n}\",\"duration_ms\":\$((n % 250))}"
                done
                sleep 1
              done
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              memory: 64Mi
EOF
done

kubectl get namespaces -L "${TEAM_LABEL//\//\\/}" 2>/dev/null || kubectl get namespaces --show-labels | grep -E 'platform-dev|payments-dev'
