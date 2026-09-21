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

# Counts the files that actually hold log data under a job's prefix.
#
# MinIO stores each object as a directory containing an xl.meta and a version
# directory holding the data as part.N. Deleting an object removes the version
# directory but leaves a small xl.meta tombstone behind, so counting objects or
# directory entries counts things that are no longer there. The data parts are
# the question worth asking: whether the logs are still on disk.
#
# The counting is done on this side rather than in the container, whose image
# has neither grep nor find.
data_parts() { # data_parts <job id>
  kubectl --context "${KUBE_CONTEXT:-k3d-loggate}" exec -n "${OBS_NS:-observability}" \
    deploy/minio -- sh -c "ls -R /export/${BUCKET:-loggate-exports}/jobs/$1 2>/dev/null" 2>/dev/null \
    | grep -c '^part\.[0-9]' | tr -d '[:space:]'
}

summary() {
  printf '\npassed %d, failed %d\n\n' "$pass" "$fail"
  [[ "$fail" -eq 0 ]]
}
