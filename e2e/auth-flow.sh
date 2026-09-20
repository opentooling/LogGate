#!/usr/bin/env bash
# End-to-end check of authentication and namespace authorization against a
# deployed LogGate: drives the real OIDC authorization code flow through
# Keycloak with a cookie jar, then asserts what each user may and may not do.
#
#   e2e/auth-flow.sh
#
# Exits non-zero on the first failed assertion.
set -uo pipefail

APP="${APP_URL:-http://loggate.localtest.me:8088}"
PASSWORD="${DEMO_PASSWORD:-loggate}"
NAMESPACE="${APP_NS:-loggate}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"

pass=0
fail=0

ok()   { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s\n     expected: %s\n     actual:   %s\n' "$1" "$2" "$3"; fail=$((fail+1)); }
check(){ # check <description> <expected> <actual>
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1" "$2" "$3"; fi
}

# Signs a user in and leaves an authenticated session in the cookie jar.
login() {
  local user="$1" jar="$2"
  rm -f "$jar"

  # 1. The app redirects to Keycloak's authorization endpoint.
  local login_page
  login_page="$(curl -sL -c "$jar" -b "$jar" "$APP/oauth2/authorization/keycloak")"

  # 2. Keycloak renders a login form whose action carries the session code.
  local action
  action="$(printf '%s' "$login_page" \
    | grep -o 'action="[^"]*login-actions/authenticate[^"]*"' \
    | head -1 | sed 's/^action="//; s/"$//' | sed 's/&amp;/\&/g')"
  if [[ -z "$action" ]]; then
    # Already authenticated at Keycloak: the SSO session short-circuits the form.
    return 0
  fi

  # 3. Submitting it redirects back to the app, which exchanges the code and
  #    establishes the session cookie in the jar.
  curl -sL -c "$jar" -b "$jar" \
    --data-urlencode "username=$user" \
    --data-urlencode "password=$PASSWORD" \
    --data-urlencode "credentialId=" \
    "$action" -o /dev/null
}

api() { # api <jar> <method> <path> [json body]
  local jar="$1" method="$2" path="$3" body="${4:-}"
  local csrf
  csrf="$(grep -i 'XSRF-TOKEN' "$jar" 2>/dev/null | awk '{print $NF}' | tail -1)"
  if [[ -n "$body" ]]; then
    curl -s -b "$jar" -c "$jar" -X "$method" \
      -H "Content-Type: application/json" \
      ${csrf:+-H "X-XSRF-TOKEN: $csrf"} \
      -d "$body" -w '\n%{http_code}' "$APP$path"
  else
    curl -s -b "$jar" -c "$jar" -X "$method" \
      ${csrf:+-H "X-XSRF-TOKEN: $csrf"} \
      -w '\n%{http_code}' "$APP$path"
  fi
}

status() { printf '%s' "$1" | tail -1; }
body()   { printf '%s' "$1" | sed '$d'; }

jq_get() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null; }

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

echo
printf 'passed %d, failed %d\n\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
