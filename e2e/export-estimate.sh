#!/usr/bin/env bash
# End-to-end check of export sizing against a deployed LogGate and a real Loki
# holding real logs: authorization first, then Loki's index volume API, then a
# window plan derived from it.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

FROM="$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '1 hour ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

echo
echo "LogGate export estimation end-to-end against $APP"
echo "  range $FROM .. $TO"
echo

JAR="$(mktemp)"
login alice "$JAR"

echo "alice estimates her own namespace"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "the estimate is returned" "200" "$(status "$r")"
check "the selector is generated server-side" '{namespace="platform-dev"}' "$(body "$r" | jq_get 'd["selector"]')"
check_at_least "Loki reports real bytes for the range" 1000 "$(body "$r" | jq_get 'd["estimatedBytes"]')"
check_at_least "the volume is broken down per namespace" 1000 "$(body "$r" | jq_get 'd["bytesByNamespace"]["platform-dev"]')"
check_at_least "a window plan is derived from it" 1 "$(body "$r" | jq_get 'd["windowCount"]')"
check_at_least "windows have a duration" 60 "$(body "$r" | jq_get 'd["windowSeconds"]')"

echo
echo "filters are applied to the generated selector"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"podPattern\":\"platform-api-*\",\"lineFilter\":\"request handled\",\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "the request is accepted" "200" "$(status "$r")"
check "the pod glob became an escaped regex" \
  '{namespace="platform-dev", pod=~"platform\\-api\\-.*"} |= "request handled"' \
  "$(body "$r" | jq_get 'd["selector"]')"

echo
echo "authorization still applies to sizing"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"payments-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "another team's namespace is refused" "403" "$(status "$r")"
check "and Loki is never asked about it" "NOT_A_GROUP_MEMBER" "$(body "$r" | jq_get 'd["denied"]["payments-dev"]')"

echo
echo "invalid requests"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"from\":\"$TO\",\"to\":\"$FROM\"}")"
check "a reversed range is rejected" "400" "$(status "$r")"

rm -f "$JAR"
summary
