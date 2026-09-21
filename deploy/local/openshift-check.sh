#!/usr/bin/env bash
# Checks that the chart's OpenShift mode really runs under OpenShift's rules,
# without an OpenShift cluster.
#
# Installs a second release, in open access mode as the production example
# configures it, into a namespace that enforces the restricted Pod Security
# Standard, with every pod given an OpenShift-style random UID by a
# post-renderer. Then it signs in and runs an export through it.
#
# Helm 3 does not pass test hooks through a post-renderer, so the test pod
# runs with no assigned ID at all. That is the stricter case: it only starts if
# its image has a numeric user, which is why the chart's test uses the
# application's own image.
#
# Needs the local stack deployed (deploy/local/deploy.sh) and the remote
# cluster seeded (deploy/local/seed-remote-cluster.sh). KEEP=1 leaves the
# release installed afterwards.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
NS="${OCP_NS:-loggate-ocp}"
HOST_PORT="${HOST_PORT:-8088}"
export APP_URL="http://ocp-loggate.localtest.me:${HOST_PORT}"

cd "$REPO_ROOT/e2e"
# shellcheck source=e2e/lib.sh
source ./lib.sh

cleanup() {
  if [[ "${KEEP:-0}" != "1" ]]; then
    helm --kube-context "$CONTEXT" uninstall loggate -n "$NS" >/dev/null 2>&1
    kubectl --context "$CONTEXT" delete namespace "$NS" --wait=false >/dev/null 2>&1
  fi
}
trap cleanup EXIT

echo
echo "OpenShift mode under restricted Pod Security, with OpenShift-style UIDs"
echo

# A previous run's namespace may still be terminating; installing into it
# fails, so wait for it to go.
kubectl --context "$CONTEXT" wait --for=delete "namespace/$NS" --timeout=300s >/dev/null 2>&1
kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f - >/dev/null
kubectl --context "$CONTEXT" label namespace "$NS" --overwrite \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/warn=restricted >/dev/null

TAG="$(kubectl --context "$CONTEXT" get deploy loggate -n loggate -o jsonpath='{.spec.template.spec.containers[0].image}' | cut -d: -f2)"
INGRESS_IP="$(kubectl --context "$CONTEXT" -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.spec.clusterIP}')"

install_log="$(mktemp)"
if helm --kube-context "$CONTEXT" upgrade --install loggate "$REPO_ROOT/deploy/helm/loggate" -n "$NS" \
    -f "$REPO_ROOT/deploy/local/values-local.yaml" \
    --set openshift.enabled=true \
    --set replicaCount=1 \
    --set access.mode=open \
    --set access.openRole=export-logs \
    --set ingress.host=ocp-loggate.localtest.me \
    --set keycloak.ingress.host=ocp-auth.localtest.me \
    --set "keycloak.publicUrl=http://ocp-auth.localtest.me:${HOST_PORT}" \
    --set "keycloak.appUrl=${APP_URL}" \
    --set-string "image.tag=$TAG" \
    --set-string "hostAliases[0].ip=$INGRESS_IP" \
    --set-string "hostAliases[0].hostnames[0]=ocp-auth.localtest.me" \
    --post-renderer "$REPO_ROOT/deploy/local/openshift-uids.py" \
    --wait --timeout 10m >"$install_log" 2>&1; then
  ok "installs and becomes ready"
else
  bad "installs and becomes ready" "success" "$(tail -5 "$install_log")"
  kubectl --context "$CONTEXT" get pods,events -n "$NS" 2>&1 | tail -20
  summary; exit 1
fi
warnings="$(grep -ci 'would violate PodSecurity' "$install_log")"
check "without a Pod Security warning" "0" "${warnings:-0}"

echo
echo "every pod runs as the ID it was given"
for pod in $(kubectl --context "$CONTEXT" get pods -n "$NS" -o name | grep -v test); do
  uid="$(kubectl --context "$CONTEXT" exec -n "$NS" "$pod" -- id -u 2>/dev/null | tr -d '[:space:]')"
  check "${pod#pod/}" "1000680000" "$uid"
done

echo
echo "the rendered release"
check "creates no RBAC in open mode" "0" \
  "$(kubectl --context "$CONTEXT" get clusterrolebinding -o name | grep -c "loggate-ocp\|$NS" | tr -d '[:space:]')"
if helm --kube-context "$CONTEXT" test loggate -n "$NS" >/dev/null 2>&1; then ok "passes helm test"; else bad "passes helm test" "success" "failure"; fi

echo
echo "and works"
JAR="$(mktemp)"
login carol "$JAR" || { summary; exit 1; }
r="$(api "$JAR" GET /api/me)"
check "carol signs in, in open mode" "OPEN" "$(body "$r" | jq_get 'd["mode"]')"
FROM="$(date -u -v-30M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '30 minutes ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
r="$(api "$JAR" POST /api/exports \
  "{\"clusters\":[\"edge-eu\"],\"namespaces\":[\"checkout-prod\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "an export is accepted" "201" "$(status "$r")"
JOB="$(body "$r" | jq_get 'd["id"]')"
state=""
for _ in $(seq 1 90); do
  state="$(body "$(api "$JAR" GET "/api/exports/$JOB")" | jq_get 'd["state"]')"
  [[ "$state" == "READY" || "$state" == "FAILED" ]] && break
  sleep 2
done
check "and runs to completion" "READY" "$state"
rm -f "$JAR" "$install_log"

summary
