#!/usr/bin/env bash
# Shared helpers for the end-to-end scripts: real OIDC sign-in through
# Keycloak with a cookie jar, and small assertion helpers.

APP="${APP_URL:-http://loggate.localtest.me:8088}"
PASSWORD="${DEMO_PASSWORD:-loggate}"

pass=0
fail=0

ok()  { printf '  \033[32mok\033[0m   %s\n' "$1"; pass=$((pass+1)); }
bad() {
  printf '  \033[31mFAIL\033[0m %s\n     expected: %s\n     actual:   %s\n' "$1" "$2" "$3"
  annotate "$1 — expected: $2, actual: $3"
  fail=$((fail+1))
}

# Under GitHub Actions, a failed check is also raised as an error annotation.
# Annotations appear on the run summary and on a pull request's diff, so the
# failing check is named where people look, without opening the step's log.
annotate() {
  [[ -n "${GITHUB_ACTIONS:-}" ]] || return 0
  local message="$1"
  # The workflow command format reserves these characters.
  message="${message//'%'/%25}"
  message="${message//$'\r'/%0D}"
  message="${message//$'\n'/%0A}"
  printf '::error title=%s::%s\n' "$(basename "$0")" "$message"
}

check() { # check <description> <expected> <actual>
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1" "$2" "$3"; fi
}

check_at_least() { # check_at_least <description> <minimum> <actual>
  local actual="${3:-}"
  if [[ "$actual" =~ ^[0-9]+$ ]] && (( actual >= $2 )); then
    ok "$1 ($actual)"
  else
    bad "$1" ">= $2" "${actual:-none}"
  fi
}

# Signs a user in, leaving an authenticated session in the cookie jar.
login() {
  local user="$1" jar="$2"
  rm -f "$jar"
  local login_page
  login_page="$(curl -sL -c "$jar" -b "$jar" "$APP/oauth2/authorization/keycloak")"

  local action
  action="$(printf '%s' "$login_page" \
    | grep -o 'action="[^"]*login-actions/authenticate[^"]*"' \
    | head -1 | sed 's/^action="//; s/"$//' | sed 's/&amp;/\&/g')"

  # No form can mean Keycloak's SSO session short-circuited the login, or that
  # something went wrong and there was never a form to fill in. Those must not
  # look the same: submit the form when there is one, then verify either way.
  if [[ -n "$action" ]]; then
    curl -sL -c "$jar" -b "$jar" \
      --data-urlencode "username=$user" \
      --data-urlencode "password=$PASSWORD" \
      --data-urlencode "credentialId=" \
      "$action" -o /dev/null
  fi

  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" "$APP/api/me")"
  if [[ "$code" != "200" ]]; then
    printf '  \033[31mFAIL\033[0m could not sign in as %s (/api/me returned %s)\n' "$user" "$code"
    if [[ -z "$action" ]]; then
      printf '     no login form was served; the first 200 characters were:\n     %s\n' \
        "$(printf '%s' "$login_page" | tr -d '\n' | cut -c1-200)"
    fi
    annotate "could not sign in as $user (/api/me returned $code)"
    fail=$((fail+1))
    return 1
  fi
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

# Counts the objects the storage holds under a job's prefix.
#
# Asked through the S3 API rather than read off MinIO's disk. Its on-disk
# format misleads twice over: a deleted object leaves an xl.meta tombstone
# behind, and a small object has no data file of its own at all, being stored
# inline in its xl.meta. A listing is the storage's own answer to the only
# question that matters here, whether the object is still there.
#
# The keys are read from the chart's secret, the same ones the application
# signs with, and are never printed.
stored_objects() { # stored_objects <job id>
  local context="${KUBE_CONTEXT:-k3d-loggate}" ns="${APP_NS:-loggate}"
  local key secret
  key="$(kubectl --context "$context" get secret -n "$ns" loggate-secrets \
    -o jsonpath='{.data.storage-access-key}' | base64 --decode)"
  secret="$(kubectl --context "$context" get secret -n "$ns" loggate-secrets \
    -o jsonpath='{.data.storage-secret-key}' | base64 --decode)"
  local listing
  listing="$(curl -s --aws-sigv4 "aws:amz:${S3_REGION:-us-east-1}:s3" --user "$key:$secret" \
    "${S3_URL:-http://s3.localtest.me:8088}/${BUCKET:-loggate-exports}?list-type=2&prefix=jobs/$1/")"
  # A refused or failed listing has no keys in it either, and must not read as
  # "nothing is stored" to a check that expects exactly that.
  if [[ "$listing" != *"<ListBucketResult"* ]]; then
    printf 'listing-failed'
    return
  fi
  printf '%s' "$listing" | grep -o '<Key>' | wc -l | tr -d '[:space:]'
}

summary() {
  printf '\npassed %d, failed %d\n\n' "$pass" "$fail"
  [[ "$fail" -eq 0 ]]
}
