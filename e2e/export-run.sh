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
# Asked through the S3 API, as any store answers it: a part per window, and
# the manifest beside them.
stored="$(stored_objects "$JOB_ID")"
windows="$(body "$(api "$JAR" GET "/api/exports/$JOB_ID")" | jq_get 'd["windowsTotal"]')"
check_at_least "a part exists per window, and the manifest" "$((windows + 1))" "$stored"

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

# --- raw output ---------------------------------------------------------------
echo
echo "an export can hold the raw log lines instead of JSON"
r="$(api "$JAR" POST /api/exports \
  "{\"namespaces\":[\"platform-dev\"],\"podPattern\":\"platform-api-*\",\"format\":\"RAW\",\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "a raw export is accepted" "201" "$(status "$r")"
RAW_ID="$(body "$r" | jq_get 'd["id"]')"
check "and says so" "RAW" "$(body "$r" | jq_get 'd["format"]')"
state=""
for _ in $(seq 1 60); do
  state="$(body "$(api "$JAR" GET "/api/exports/$RAW_ID")" | jq_get 'd["state"]')"
  [[ "$state" == "READY" || "$state" == "FAILED" || "$state" == "CANCELLED" ]] && break
  sleep 2
done
check "it finishes ready" "READY" "$state"
r="$(api "$JAR" GET "/api/exports/$RAW_ID/downloads")"
check "its parts are named as plain logs" "yes" \
  "$(body "$r" | jq_get '"yes" if any(f["name"].endswith(".log.gz") for f in d) and not any(f["name"].endswith(".jsonl.gz") for f in d) else "no"')"
PART_URL="$(body "$r" | jq_get 'next(f["url"] for f in d if f["name"].endswith(".log.gz"))')"
first_line="$(curl -s "$PART_URL" | gunzip 2>/dev/null | head -1)"
check "and hold the lines as logged, not wrapped in JSON" "no" \
  "$([[ "$first_line" == \{\"timestamp* || "$first_line" == \{\"labels* || -z "$first_line" ]] && echo yes || echo no)"
# Loki keeps the newline each program wrote; ending every entry with another
# would leave a blank line after each one.
check "one line per entry, with no blank lines between them" "0" \
  "$(curl -s "$PART_URL" | gunzip 2>/dev/null | grep -c '^$')"

rm -f "$JAR"
summary
