#!/usr/bin/env bash
# End-to-end check of clusters and the two access modes.
#
# Loki holds two clusters' logs: this one (labelled by Alloy) and edge-eu,
# pushed by deploy/local/seed-remote-cluster.sh, which LogGate has no
# Kubernetes access to. edge-eu deliberately has a platform-dev too.
#
#   1. Team-label mode pins every export to this cluster, so a team's
#      platform-dev never includes edge-eu's platform-dev.
#   2. Open mode offers every cluster and namespace Loki holds, to holders of a
#      client role granted through a group, and to nobody else; and an export
#      from the unreachable cluster returns exactly what Loki holds.
#
# Open mode is switched on for the second part and put back afterwards, even
# on failure.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

NAMESPACE="${APP_NS:-loggate}"
OBS_NS="${OBS_NS:-observability}"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
LOCAL="${LOCAL_CLUSTER:-k3d-loggate}"
REMOTE="${REMOTE_CLUSTER:-edge-eu}"
ROLE="${OPEN_ROLE:-export-logs}"

env_of() { kubectl --context "$CONTEXT" get deploy loggate -n "$NAMESPACE" \
  -o jsonpath="{.spec.template.spec.containers[0].env[?(@.name==\"$1\")].value}" 2>/dev/null; }
set_env() { kubectl --context "$CONTEXT" set env deploy/loggate -n "$NAMESPACE" "$@" >/dev/null 2>&1
  kubectl --context "$CONTEXT" rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1; }

# Loki's own answers, for comparing LogGate's against.
enc() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$1"; }
loki() { kubectl --context "$CONTEXT" get --raw \
  "/api/v1/namespaces/$OBS_NS/services/loki-gateway:80/proxy/loki/api/v1/$1" 2>/dev/null; }
loki_bytes() { # loki_bytes <selector> <from epoch> <to epoch>
  loki "index/volume?query=$(enc "$1")&start=${2}000000000&end=${3}000000000" \
    | python3 -c 'import json,sys; print(sum(int(r["value"][1]) for r in json.load(sys.stdin)["data"]["result"]))'; }
loki_lines() { # loki_lines <selector> <to epoch> <seconds>
  loki "query?query=$(enc "sum(count_over_time($1[${3}s]))")&time=${2}000000000" \
    | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; print(int(float(r[0]["value"][1])) if r else 0)'; }

NOW="$(date -u +%s)"
FROM_EPOCH=$((NOW - 1800))
FROM="$(date -u -r "$FROM_EPOCH" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d "@$FROM_EPOCH" '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u -r "$NOW" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d "@$NOW" '+%Y-%m-%dT%H:%M:%SZ')"

echo
echo "LogGate clusters and access modes against $APP"
echo "  range $FROM .. $TO"
echo

JAR="$(mktemp)"

# --- team-label mode: pinned to this cluster ----------------------------------
echo "team-label mode pins every export to this cluster"
login alice "$JAR" || { summary; exit 1; }
r="$(api "$JAR" GET /api/me)"
check "the mode is team-label" "TEAM_LABEL" "$(body "$r" | jq_get 'd["mode"]')"
check "the only cluster on offer is this one" "$LOCAL" "$(body "$r" | jq_get '",".join(d["clusters"])')"

r="$(api "$JAR" POST /api/exports/estimate \
  "{\"clusters\":[\"$REMOTE\"],\"namespaces\":[\"platform-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "another cluster is refused" "403" "$(status "$r")"
check "as a cluster it cannot vouch for" "UNKNOWN_CLUSTER" "$(body "$r" | jq_get "d['denied']['cluster/$REMOTE']")"

r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
pinned="$(body "$r" | jq_get 'd["estimate"]["estimatedBytes"]')"
local_only="$(loki_bytes "{cluster=\"$LOCAL\", namespace=\"platform-dev\"}" "$FROM_EPOCH" "$NOW")"
remote_only="$(loki_bytes "{cluster=\"$REMOTE\", namespace=\"platform-dev\"}" "$FROM_EPOCH" "$NOW")"
check_at_least "the remote cluster really has a platform-dev" 1 "$remote_only"
# Alice's size is this cluster's platform-dev alone. Logs keep arriving between
# the two questions, so it must be within 5% of Loki's answer for this cluster,
# and nowhere near this cluster's plus the remote one's.
check "and alice's export is sized on this cluster's alone ($pinned vs $local_only here, $remote_only remote)" "yes" \
  "$(python3 -c "print('yes' if abs($pinned - $local_only) <= $local_only * 0.05 and $pinned < $local_only + $remote_only * 0.5 else 'no')")"

# --- open mode ----------------------------------------------------------------
echo
echo "open mode, for holders of the $ROLE role"
ORIGINAL_MODE="$(env_of ACCESS_MODE)"
ORIGINAL_ROLE="$(env_of ACCESS_OPEN_ROLE)"
restore() {
  set_env "ACCESS_MODE=${ORIGINAL_MODE:-TEAM_LABEL}" "ACCESS_OPEN_ROLE=${ORIGINAL_ROLE}"
}
trap restore EXIT
set_env ACCESS_MODE=OPEN "ACCESS_OPEN_ROLE=$ROLE"

login carol "$JAR" || { summary; exit 1; }
r="$(api "$JAR" GET /api/me)"
check "carol, in the group that holds the role, is let in" "None" "$(body "$r" | jq_get 'd["barrier"]')"
check "the mode is open" "OPEN" "$(body "$r" | jq_get 'd["mode"]')"
check "an export may name no namespaces" "True" "$(body "$r" | jq_get 'd["namespacesOptional"]')"
check "every cluster Loki holds is offered" "$REMOTE,$LOCAL" "$(body "$r" | jq_get '",".join(sorted(d["clusters"]))')"
check "but no namespaces until a cluster is chosen, sparing Loki the widest query" "0" \
  "$(body "$r" | jq_get 'len(d["namespaces"])')"
r="$(api "$JAR" GET /api/namespaces)"
check "and asking for every cluster's namespaces at once is refused" "400" "$(status "$r")"

r="$(api "$JAR" GET "/api/namespaces?cluster=$REMOTE")"
check "namespaces come from Loki, for a cluster LogGate cannot reach" "checkout-prod,platform-dev" \
  "$(body "$r" | jq_get '",".join(n["name"] for n in d)')"
r="$(api "$JAR" GET "/api/namespaces?cluster=$LOCAL")"
check "and include namespaces no team owns" "yes" \
  "$(body "$r" | jq_get '"yes" if "observability" in [n["name"] for n in d] else "no"')"

r="$(api "$JAR" POST /api/exports/estimate \
  "{\"clusters\":[\"$REMOTE\"],\"namespaces\":[],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "sizing every namespace of the remote cluster is allowed" "200" "$(status "$r")"
check "as a query over every namespace there" "{cluster=\"$REMOTE\", namespace=~\".+\"}" \
  "$(body "$r" | jq_get 'd["estimate"]["selector"]')"
check "broken down by namespace" "checkout-prod,platform-dev" \
  "$(body "$r" | jq_get '",".join(sorted(d["estimate"]["bytesByNamespace"]))')"

r="$(api "$JAR" POST /api/exports/estimate \
  "{\"clusters\":[\"no-such-cluster\"],\"namespaces\":[],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "a cluster Loki has never heard of is refused" "UNKNOWN_CLUSTER" \
  "$(body "$r" | jq_get 'd["denied"]["cluster/no-such-cluster"]')"

echo
echo "an export from the unreachable cluster"
r="$(api "$JAR" POST /api/exports \
  "{\"clusters\":[\"$REMOTE\"],\"namespaces\":[\"checkout-prod\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "is accepted" "201" "$(status "$r")"
JOB="$(body "$r" | jq_get 'd["id"]')"
state=""
for _ in $(seq 1 90); do
  job="$(body "$(api "$JAR" GET "/api/exports/$JOB")")"
  state="$(printf '%s' "$job" | jq_get 'd["state"]')"
  [[ "$state" == "READY" || "$state" == "FAILED" ]] && break
  sleep 2
done
check "and runs to completion" "READY" "$state"
check "records the cluster it came from" "$REMOTE" "$(printf '%s' "$job" | jq_get '",".join(d["clusters"])')"
expected="$(loki_lines "{cluster=\"$REMOTE\", namespace=\"checkout-prod\"}" "$NOW" $((NOW - FROM_EPOCH)))"
check "with exactly the lines Loki holds for it" "$expected" "$(printf '%s' "$job" | jq_get 'd["entriesWritten"]')"

r="$(api "$JAR" GET /api/quota)"
check "and is charged to carol, there being no team" "carol" "$(body "$r" | jq_get 'd["budgets"][0]["label"]')"

echo
echo "without the role"
login bob "$JAR" || { summary; exit 1; }
r="$(api "$JAR" GET /api/me)"
check "bob, in a team but not the group, is told what he lacks" "yes" \
  "$(body "$r" | jq_get "'yes' if '\"$ROLE\" role' in (d['barrier'] or '') else 'no'")"
check "and offered nothing" "0" "$(body "$r" | jq_get 'len(d["namespaces"]) + len(d["clusters"])')"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"payments-dev\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "even his own team's namespace is refused" "MISSING_ROLE" "$(body "$r" | jq_get 'd["denied"]["payments-dev"]')"
r="$(api "$JAR" GET "/api/exports/$JOB")"
check "and carol's export is not his to see" "404" "$(status "$r")"

restore
trap - EXIT
echo
echo "back to team-label mode"
login alice "$JAR" >/dev/null
check "the mode is restored" "TEAM_LABEL" "$(body "$(api "$JAR" GET /api/me)" | jq_get 'd["mode"]')"

rm -f "$JAR"
summary
