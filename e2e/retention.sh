#!/usr/bin/env bash
# End-to-end check of the two things that are supposed to stop being available.
#
#   1. A download link expires, and the object storage refuses it afterwards.
#   2. An export's retention runs out, its files are deleted from the bucket,
#      and the export can no longer be downloaded.
#
# Both are verified against the real MinIO rather than the database alone: a
# row that says EXPIRED while the logs are still sitting in a bucket is exactly
# the failure this is written to catch.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

NAMESPACE="${APP_NS:-loggate}"
OBS_NS="${OBS_NS:-observability}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
BUCKET="${BUCKET:-loggate-exports}"

psql() { kubectl --context "$CONTEXT" exec -n "$NAMESPACE" "$NAMESPACE-postgres-0" -- \
  psql -U loggate -d loggate -tAc "$1" 2>/dev/null | tr -d '[:space:]'; }


FROM="$(date -u -v-20M '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '20 minutes ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

echo
echo "LogGate retention and link expiry against $APP"
echo

JAR="$(mktemp)"
login alice "$JAR" || { summary; exit 1; }

# --- an export to expire ------------------------------------------------------
echo "an export is run so there is something to expire"
r="$(api "$JAR" POST /api/exports \
  "{\"namespaces\":[\"platform-dev\"],\"podPattern\":\"platform-api-*\",\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "the export is accepted" "201" "$(status "$r")"
JOB="$(body "$r" | jq_get 'd["id"]')"

state=""
for _ in $(seq 1 90); do
  state="$(body "$(api "$JAR" GET "/api/exports/$JOB")" | jq_get 'd["state"]')"
  [[ "$state" == "READY" || "$state" == "FAILED" || "$state" == "CANCELLED" ]] && break
  sleep 2
done
check "it reaches ready" "READY" "$state"
[[ "$state" != "READY" ]] && { rm -f "$JAR"; summary; exit 1; }

check_at_least "its files are in the bucket" 1 "$(stored_objects "$JOB")"
check "it is given an expiry when it is published" "yes" \
  "$(body "$(api "$JAR" GET "/api/exports/$JOB")" | jq_get '"yes" if d["expiresAt"] else "no"')"

# --- a download link stops working -------------------------------------------
echo
echo "a download link expires"
URL="$(body "$(api "$JAR" GET "/api/exports/$JOB/downloads")" | jq_get 'd[0]["url"]')"
check "a presigned link is issued" "yes" "$([[ -n "$URL" ]] && echo yes || echo no)"
check "it carries its own expiry" "yes" \
  "$([[ "$URL" == *"X-Amz-Expires="* ]] && echo yes || echo no)"
# The configured lifetime, proving the property reaches the signer rather than
# the SDK's own default being used.
check "signed for the configured lifetime" "X-Amz-Expires=1800" \
  "$(printf '%s' "$URL" | tr '&' '\n' | grep '^X-Amz-Expires=')"
check "and it works right now" "200" "$(curl -s -o /dev/null -w '%{http_code}' "$URL")"

# Changing the expiry on a signed link breaks its signature, so the storage
# refuses it. That proves the link cannot be extended by editing it; it says
# nothing about expiry.
TAMPERED="$(printf '%s' "$URL" | sed -E 's/X-Amz-Expires=[0-9]+/X-Amz-Expires=604800/')"
check "a link altered to live longer is refused" "403" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$TAMPERED")"

# Expiry itself: shorten the configured lifetime, take a genuinely signed link,
# and outlive it. The deployment is put back afterwards, even on failure.
echo
echo "a link that has outlived its lifetime is refused"
ORIGINAL_LIFETIME="$(kubectl --context "$CONTEXT" get deploy loggate -n "$NAMESPACE" \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="S3_PRESIGNED_URL_LIFETIME")].value}' 2>/dev/null)"
restore_lifetime() {
  if [[ -n "${ORIGINAL_LIFETIME:-}" ]]; then
    kubectl --context "$CONTEXT" set env deploy/loggate -n "$NAMESPACE" \
      "S3_PRESIGNED_URL_LIFETIME=$ORIGINAL_LIFETIME" >/dev/null 2>&1
    kubectl --context "$CONTEXT" rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1
  fi
}
trap restore_lifetime EXIT

LIFETIME=15
kubectl --context "$CONTEXT" set env deploy/loggate -n "$NAMESPACE" \
  "S3_PRESIGNED_URL_LIFETIME=${LIFETIME}s" >/dev/null 2>&1
kubectl --context "$CONTEXT" rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1
login alice "$JAR" >/dev/null

SHORT="$(body "$(api "$JAR" GET "/api/exports/$JOB/downloads")" | jq_get 'd[0]["url"]')"
check "the link is signed for the shortened lifetime" "X-Amz-Expires=$LIFETIME" \
  "$(printf '%s' "$SHORT" | tr '&' '\n' | grep '^X-Amz-Expires=')"
check "it works while it is valid" "200" "$(curl -s -o /dev/null -w '%{http_code}' "$SHORT")"
sleep $((LIFETIME + 5))
response="$(curl -s -w '\n%{http_code}' "$SHORT")"
check "it is refused once it has expired" "403" "$(status "$response")"
# A refusal for any other reason, such as a bad signature, would also be a 403,
# so the storage's own explanation is checked too.
check "and refused because it expired" "yes" \
  "$(body "$response" | grep -qi 'expired' && echo yes || echo no)"

restore_lifetime
trap - EXIT
login alice "$JAR" >/dev/null

# --- retention deletes the files ---------------------------------------------
echo
echo "retention deletes the files"
psql "UPDATE export_job SET expires_at = now() - interval '1 minute' WHERE id = '$JOB'" >/dev/null

swept=""
for _ in $(seq 1 30); do
  swept="$(psql "SELECT state FROM export_job WHERE id = '$JOB'")"
  [[ "$swept" == "EXPIRED" ]] && break
  sleep 2
done
check "the export is marked expired" "EXPIRED" "$swept"
check "and its files are gone from the bucket" "0" "$(stored_objects "$JOB")"

check "the API reports it as expired" "EXPIRED" \
  "$(body "$(api "$JAR" GET "/api/exports/$JOB")" | jq_get 'd["state"]')"
check "the archive is no longer downloadable" "409" \
  "$(status "$(api "$JAR" GET "/api/exports/$JOB/archive.zip")")"
check "nor the download script" "409" \
  "$(status "$(api "$JAR" GET "/api/exports/$JOB/download.sh")")"

rm -f "$JAR"
summary
