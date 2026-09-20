#!/usr/bin/env bash
# End-to-end check of authentication and namespace authorization against a
# deployed LogGate: drives the real OIDC authorization code flow through
# Keycloak with a cookie jar, then asserts what each user may and may not do.
#
#   e2e/auth-flow.sh
#
# Exits non-zero on the first failed assertion.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

NAMESPACE="${APP_NS:-loggate}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"

echo
echo "LogGate authorization end-to-end against $APP"
echo

# --- unauthenticated ---------------------------------------------------------
echo "unauthenticated"
code="$(curl -s -o /dev/null -w '%{http_code}' "$APP/api/me")"
check "GET /api/me is refused with 401, not a redirect" "401" "$code"

code="$(curl -s -o /dev/null -w '%{http_code}' "$APP/actuator/health")"
check "health endpoint stays public" "200" "$code"

# --- alice: platform only ----------------------------------------------------
echo
echo "alice (ad-platform-dev)"
ALICE_JAR="$(mktemp)"
login alice "$ALICE_JAR"

r="$(api "$ALICE_JAR" GET /api/me)"
check "signs in and /api/me returns 200" "200" "$(status "$r")"
check "identifies the caller" "alice" "$(body "$r" | jq_get 'd["name"]')"
check "carries the group claim" "ad-platform-dev" "$(body "$r" | jq_get '",".join(d["groups"])')"
check "sees only its own namespace" "platform-dev" "$(body "$r" | jq_get '",".join(n["name"] for n in d["namespaces"])')"

r="$(api "$ALICE_JAR" POST /api/namespaces/authorize '{"namespaces":["platform-dev"]}')"
check "is allowed its own namespace" "200" "$(status "$r")"
check "the decision names it" "platform-dev" "$(body "$r" | jq_get '",".join(d["allowed"])')"

r="$(api "$ALICE_JAR" POST /api/namespaces/authorize '{"namespaces":["payments-dev"]}')"
check "is refused another team's namespace" "403" "$(status "$r")"
check "and told why" "NOT_A_GROUP_MEMBER" "$(body "$r" | jq_get 'd["denied"]["payments-dev"]')"

r="$(api "$ALICE_JAR" POST /api/namespaces/authorize '{"namespaces":["kube-system"]}')"
check "is refused an unlabelled namespace" "403" "$(status "$r")"
check "as an unknown namespace" "UNKNOWN_NAMESPACE" "$(body "$r" | jq_get 'd["denied"]["kube-system"]')"

r="$(api "$ALICE_JAR" POST /api/namespaces/authorize '{"namespaces":["platform-dev","payments-dev"]}')"
check "a mixed request is refused as a whole" "403" "$(status "$r")"
check "but still reports what was allowed" "platform-dev" "$(body "$r" | jq_get '",".join(d["allowed"])')"

r="$(api "$ALICE_JAR" POST /api/namespaces/authorize '{"namespaces":[]}')"
check "an empty request is rejected" "400" "$(status "$r")"

# --- audit -------------------------------------------------------------------
echo
echo "audit trail"
denials="$(kubectl --context "$CONTEXT" exec -n "$NAMESPACE" "$NAMESPACE-postgres-0" -- \
  psql -U loggate -d loggate -tAc \
  "SELECT count(*) FROM audit_event WHERE action = 'NAMESPACE_ACCESS_DENIED'" 2>/dev/null | tr -d '[:space:]')"
if [[ "${denials:-0}" -ge 3 ]]; then
  ok "refusals are recorded in audit_event ($denials rows)"
else
  bad "refusals are recorded in audit_event" ">=3 rows" "${denials:-none}"
fi

detail="$(kubectl --context "$CONTEXT" exec -n "$NAMESPACE" "$NAMESPACE-postgres-0" -- \
  psql -U loggate -d loggate -tAc \
  "SELECT detail::text FROM audit_event WHERE action = 'NAMESPACE_ACCESS_DENIED' ORDER BY at DESC LIMIT 1" 2>/dev/null)"
if [[ "$detail" == *"denied"* && "$detail" == *"groups"* ]]; then
  ok "the audit detail records what was denied and to whom"
else
  bad "the audit detail records what was denied and to whom" "denied + groups keys" "$detail"
fi

# --- dave: no groups at all --------------------------------------------------
echo
echo "dave (no groups)"
DAVE_JAR="$(mktemp)"
login dave "$DAVE_JAR"

r="$(api "$DAVE_JAR" GET /api/namespaces)"
check "signs in successfully" "200" "$(status "$r")"
check "but sees no namespaces at all" "" "$(body "$r" | jq_get '",".join(n["name"] for n in d)')"

r="$(api "$DAVE_JAR" POST /api/namespaces/authorize '{"namespaces":["platform-dev"]}')"
check "is refused everything" "403" "$(status "$r")"

rm -f "$ALICE_JAR" "$DAVE_JAR"

summary
