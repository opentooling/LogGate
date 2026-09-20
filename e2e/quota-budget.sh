#!/usr/bin/env bash
# End-to-end check of the per-team daily budget.
#
# The budget is measured over a rolling window, so spending it in a test would
# block ordinary runs for a day. Instead this tightens the limit, proves the
# refusal, and puts the limit back.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

NAMESPACE="${APP_NS:-loggate}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
ORIGINAL="$(kubectl --context "$CONTEXT" get deploy loggate -n "$NAMESPACE" \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="QUOTA_DAILY_BYTES_PER_TEAM")].value}' 2>/dev/null)"

restore() {
  if [[ -n "${ORIGINAL:-}" ]]; then
    kubectl --context "$CONTEXT" set env deploy/loggate -n "$NAMESPACE" \
      "QUOTA_DAILY_BYTES_PER_TEAM=$ORIGINAL" >/dev/null 2>&1
    kubectl --context "$CONTEXT" rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1
  fi
}
trap restore EXIT

FROM="$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '1 hour ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

echo
echo "LogGate per-team daily budget against $APP"
echo

echo "with a one-megabyte budget"
kubectl --context "$CONTEXT" set env deploy/loggate -n "$NAMESPACE" \
  QUOTA_DAILY_BYTES_PER_TEAM=1048576 >/dev/null 2>&1
kubectl --context "$CONTEXT" rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1

JAR="$(mktemp)"
login alice "$JAR" || { summary; exit 1; }

r="$(api "$JAR" POST /api/exports "{\"namespaces\":[\"platform-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "an export over the budget is refused" "429" "$(status "$r")"
check "the team is named" "yes" \
  "$(body "$r" | jq_get '"yes" if "platform has exported" in d["message"] else "no"')"
check "and the limit is stated" "yes" \
  "$(body "$r" | jq_get '"yes" if "1.0 MB allowed" in d["message"] else "no"')"

echo
echo "the refusal is recorded"
refusals="$(kubectl --context "$CONTEXT" exec -n "$NAMESPACE" "$NAMESPACE-postgres-0" -- \
  psql -U loggate -d loggate -tAc \
  "SELECT count(*) FROM audit_event WHERE action = 'EXPORT_REFUSED'" 2>/dev/null | tr -d '[:space:]')"
check_at_least "an EXPORT_REFUSED audit entry exists" 1 "$refusals"

echo
echo "with the budget restored"
restore
trap - EXIT
login alice "$JAR" >/dev/null 2>&1
r="$(api "$JAR" POST /api/exports "{\"namespaces\":[\"platform-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "the same export is accepted again" "201" "$(status "$r")"
JOB="$(body "$r" | jq_get 'd["id"]')"
[[ -n "${JOB:-}" ]] && api "$JAR" POST "/api/exports/$JOB/cancel" >/dev/null 2>&1

rm -f "$JAR"
summary
