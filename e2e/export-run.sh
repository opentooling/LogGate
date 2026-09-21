#!/usr/bin/env bash
# End-to-end check that an export actually runs: submit it, watch the windows
# finish, and confirm the parts landed in object storage.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

NAMESPACE="${APP_NS:-loggate}"
OBS_NS="${OBS_NS:-observability}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
BUCKET="${S3_BUCKET:-loggate-exports}"

# Long enough to still be running when the cancellation arrives: a job that
# finishes first would make the cancellation test pass or fail on timing.
WIDE_FROM="$(date -u -v-47H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '47 hours ago' '+%Y-%m-%dT%H:%M:%SZ')"
FROM="$(date -u -v-30M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '30 minutes ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
LONG_AGO="$(date -u -v-5d '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '5 days ago' '+%Y-%m-%dT%H:%M:%SZ')"

echo
echo "LogGate export execution end-to-end against $APP"
echo "  range $FROM .. $TO"
echo

JAR="$(mktemp)"
login alice "$JAR" || { summary; exit 1; }

# --- a real export, run to completion ---------------------------------------
echo "an export runs to completion"
r="$(api "$JAR" POST /api/exports "{\"namespaces\":[\"platform-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "the export is accepted" "201" "$(status "$r")"
JOB_ID="$(body "$r" | jq_get 'd["id"]')"
check_at_least "it is planned into windows" 1 "$(body "$r" | jq_get 'd["windowsTotal"]')"

state=""
for _ in $(seq 1 60); do
  r="$(api "$JAR" GET "/api/exports/$JOB_ID")"
  state="$(body "$r" | jq_get 'd["state"]')"
  [[ "$state" == "READY" || "$state" == "FAILED" || "$state" == "CANCELLED" ]] && break
  sleep 2
done

check "it finishes ready" "READY" "$state"
r="$(api "$JAR" GET "/api/exports/$JOB_ID")"
check "every window completed" \
  "$(body "$r" | jq_get 'd["windowsTotal"]')" "$(body "$r" | jq_get 'd["windowsDone"]')"
check "progress reaches one" "1.0" "$(body "$r" | jq_get 'd["progress"]')"
check_at_least "log entries were extracted" 1 "$(body "$r" | jq_get 'd["entriesWritten"]')"
check_at_least "bytes were written" 1 "$(body "$r" | jq_get 'd["bytesWritten"]')"

# --- the artifacts are really in object storage ------------------------------
echo
echo "the parts are in object storage"
parts="$(kubectl --context "$CONTEXT" exec -n "$OBS_NS" deploy/minio -- \
  sh -c "ls -1 /export/$BUCKET/jobs/$JOB_ID/parts 2>/dev/null | wc -l" 2>/dev/null | tr -d '[:space:]')"
check_at_least "a part exists per window" 1 "$parts"

# --- cancellation ------------------------------------------------------------
echo
echo "an export can be cancelled"
r="$(api "$JAR" POST /api/exports "{\"namespaces\":[\"platform-dev\"],\"from\":\"$WIDE_FROM\",\"to\":\"$TO\"}")"
check "a second export is accepted" "201" "$(status "$r")"
CANCEL_ID="$(body "$r" | jq_get 'd["id"]')"
r="$(api "$JAR" POST "/api/exports/$CANCEL_ID/cancel")"
check "the cancellation is accepted" "202" "$(status "$r")"

state=""
# Cancellation completes only once in-flight windows have abandoned, so this
# waits longer than the submit checks do.
for _ in $(seq 1 60); do
  state="$(body "$(api "$JAR" GET "/api/exports/$CANCEL_ID")" | jq_get 'd["state"]')"
  [[ "$state" == "CANCELLED" || "$state" == "READY" ]] && break
  sleep 2
done
check "it reaches cancelled" "CANCELLED" "$state"

check "its artifacts were purged" "0" "$(stored_objects "$CANCEL_ID")"

# --- quotas ------------------------------------------------------------------
echo
echo "quotas refuse what they should"
r="$(api "$JAR" POST /api/exports "{\"namespaces\":[\"platform-dev\"],\"from\":\"$LONG_AGO\",\"to\":\"$TO\"}")"
check "a range longer than allowed is refused" "429" "$(status "$r")"
check "and says why" "yes" "$(body "$r" | jq_get '"yes" if "longer than" in d["message"] else "no"')"

echo
echo "authorization still applies to submission"
r="$(api "$JAR" POST /api/exports "{\"namespaces\":[\"payments-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "another team's namespace is refused" "403" "$(status "$r")"

echo
echo "listing"
r="$(api "$JAR" GET /api/exports)"
check "the caller sees their own exports" "200" "$(status "$r")"
check_at_least "including the ones just submitted" 2 "$(body "$r" | jq_get 'len(d)')"

rm -f "$JAR"
summary
