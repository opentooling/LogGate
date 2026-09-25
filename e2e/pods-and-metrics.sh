#!/usr/bin/env bash
# End-to-end check of pod listing, the activity report, and the metrics that
# the Grafana dashboard is built on, against a deployed LogGate and the local
# Prometheus and Grafana.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
# shellcheck source=e2e/lib.sh
source ./lib.sh

PROMETHEUS="${PROMETHEUS_URL:-http://prometheus.localtest.me:8088}"
GRAFANA="${GRAFANA_URL:-http://grafana.localtest.me:8088}"
GRAFANA_AUTH="${GRAFANA_USER:-admin}:${GRAFANA_PASSWORD:-loggate}"
export GRAFANA_AUTH PROMETHEUS

FROM="$(date -u -v-1H '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '1 hour ago' '+%Y-%m-%dT%H:%M:%SZ')"
TO="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
LONG_AGO="$(date -u -v-3d '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u -d '3 days ago' '+%Y-%m-%dT%H:%M:%SZ')"

echo
echo "LogGate pods, activity and metrics end-to-end against $APP"
echo

JAR="$(mktemp)"
login alice "$JAR" || { summary; exit 1; }

echo "pods are listed from kube-state-metrics"
r="$(api "$JAR" GET /api/me)"
check "the page is told pods can be listed" "True" "$(body "$r" | jq_get 'd["podsListable"]')"

# kube-state-metrics is scraped every 30 seconds, so a freshly seeded pod can
# take a minute to be listed.
pods=""
for _ in $(seq 1 24); do
  r="$(api "$JAR" GET "/api/pods?namespace=platform-dev&from=$FROM&to=$TO")"
  pods="$(body "$r" | jq_get '",".join(p["name"] for p in d["pods"])')"
  [[ "$pods" == *platform-api-* ]] && break
  sleep 5
done
check "the listing is answered" "200" "$(status "$r")"
check "from a configured source" "True" "$(body "$r" | jq_get 'd["available"]')"
check "including the seeded API pods" "yes" "$([[ "$pods" == *platform-api-* ]] && echo yes || echo no)"
check "and only this namespace's" "platform-dev" \
  "$(body "$r" | jq_get '",".join(sorted({p["namespace"] for p in d["pods"]}))')"
POD="$(body "$r" | jq_get 'next(p["name"] for p in d["pods"] if p["name"].startswith("platform-api-"))')"

r="$(api "$JAR" GET "/api/pods?namespace=payments-dev&from=$FROM&to=$TO")"
check "another team's pods are refused like its logs" "403" "$(status "$r")"
r="$(api "$JAR" GET "/api/pods?from=$FROM&to=$TO")"
check "every pod everywhere is never listed" "400" "$(status "$r")"
r="$(api "$JAR" GET "/api/pods?namespace=platform-dev&from=$LONG_AGO&to=$TO")"
check "nor over a range no export could cover" "400" "$(status "$r")"

echo
echo "a picked pod narrows the export"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"pods\":[\"$POD\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "the estimate is returned" "200" "$(status "$r")"
check "for exactly that pod" \
  "{cluster=\"k3d-loggate\", namespace=\"platform-dev\"} | pod=\"$POD\"" \
  "$(body "$r" | jq_get 'd["estimate"]["selector"]')"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"pods\":[\"$POD\"],\"podPattern\":\"api-*\",\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "picked pods and a pattern at once are refused" "400" "$(status "$r")"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"pods\":[\"x\\\"}|y\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "and a pod name that is not one is refused, not escaped" "400" "$(status "$r")"

echo
echo "the activity report, for administrators"
CAROL_JAR="$(mktemp)"
login carol "$CAROL_JAR" >/dev/null
check "carol, in the admin group, is told she is an administrator" "True" \
  "$(body "$(api "$CAROL_JAR" GET /api/me)" | jq_get 'd["admin"]')"
check "alice, who can export, is not" "False" "$(body "$(api "$JAR" GET /api/me)" | jq_get 'd["admin"]')"
check "and is refused the report" "403" "$(status "$(api "$JAR" GET /api/activity)")"
r="$(api "$CAROL_JAR" GET /api/activity)"
check "is answered" "200" "$(status "$r")"
check "hour by hour over a day" "24" "$(body "$r" | jq_get 'len(d["series"])')"
check "names no one" "none" "$(body "$r" | jq_get '"alice" in json.dumps(d) and "leaked" or "none"')"
r="$(api "$CAROL_JAR" GET "/api/activity?period=7d")"
check "and in six-hour steps over a week" "28" "$(body "$r" | jq_get 'len(d["series"])')"
r="$(api "$CAROL_JAR" GET "/api/activity?period=forever")"
check "for the periods it offers only" "400" "$(status "$r")"

DAVE_JAR="$(mktemp)"
login dave "$DAVE_JAR" >/dev/null
r="$(api "$DAVE_JAR" GET /api/activity)"
check "and not to someone who cannot export" "403" "$(status "$r")"

echo
echo "every download is audited, for administrators to see"
r="$(api "$JAR" POST /api/exports \
  "{\"namespaces\":[\"platform-dev\"],\"pods\":[\"$POD\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "a small export is accepted" "201" "$(status "$r")"
JOB="$(body "$r" | jq_get 'd["id"]')"
state=""
for _ in $(seq 1 60); do
  state="$(body "$(api "$JAR" GET "/api/exports/$JOB")" | jq_get 'd["state"]')"
  [[ "$state" == "READY" || "$state" == "FAILED" || "$state" == "CANCELLED" ]] && break
  sleep 2
done
check "and finishes ready" "READY" "$state"
check "alice opens its file links" "200" "$(status "$(api "$JAR" GET "/api/exports/$JOB/downloads")")"
check "takes the script" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$APP/api/exports/$JOB/download.sh")"
check "and the .zip" "200" \
  "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$APP/api/exports/$JOB/archive.zip")"
r="$(api "$CAROL_JAR" GET "/api/audit/downloads?limit=200")"
check "carol reads the audit trail" "200" "$(status "$r")"
check "which records all three, each by how it was taken" \
  "ARCHIVE_DOWNLOADED,DOWNLOAD_LINKS_ISSUED,DOWNLOAD_SCRIPT_ISSUED" \
  "$(body "$r" | JOB="$JOB" jq_get '",".join(sorted(e["action"] for e in d["page"]["events"] if e["jobId"] == __import__("os").environ["JOB"]))')"
check "by name, with what was taken" "alice platform-dev" \
  "$(body "$r" | JOB="$JOB" jq_get '" ".join(next((e["name"], e["namespaces"][0]) for e in d["page"]["events"] if e["jobId"] == __import__("os").environ["JOB"]))')"
check "alice, who is not an administrator, cannot read it" "403" \
  "$(status "$(api "$JAR" GET /api/audit/downloads)")"

echo
echo "metrics reach Prometheus and the Grafana dashboard"
series=""
for _ in $(seq 1 24); do
  series="$(curl -s "$PROMETHEUS/api/v1/query" --data-urlencode 'query=count(loggate_jobs_active)' \
    | jq_get 'd["data"]["result"][0]["value"][1] if d["data"]["result"] else "0"')"
  [[ "${series:-0}" -ge 1 ]] && break
  sleep 5
done
check_at_least "every replica is scraped on its management port" 2 "${series:-0}"
# Other releases may be scraped too, so this one is looked for among them.
check "the scrape is by pod, with namespace labels the dashboard filters on" "yes" \
  "$(curl -s "$PROMETHEUS/api/v1/query" --data-urlencode 'query=max by (namespace) (loggate_jobs_active)' \
    | NS="${APP_NS:-loggate}" jq_get '"yes" if __import__("os").environ["NS"] in [r["metric"].get("namespace") for r in d["data"]["result"]] else "no"')"
check_at_least "Loki query timings are there for the latency panels" 1 \
  "$(curl -s "$PROMETHEUS/api/v1/query" --data-urlencode 'query=count(loggate_loki_query_duration_seconds_bucket)' \
    | jq_get 'd["data"]["result"][0]["value"][1] if d["data"]["result"] else "0"')"

dashboard=""
for _ in $(seq 1 12); do
  dashboard="$(curl -s -u "$GRAFANA_AUTH" "$GRAFANA/api/dashboards/uid/loggate" | jq_get 'd["dashboard"]["title"]')"
  [[ "$dashboard" == "LogGate" ]] && break
  sleep 5
done
check "Grafana loaded the chart's dashboard" "LogGate" "$dashboard"
check "with a Prometheus data source to point it at" "prometheus" \
  "$(curl -s -u "$GRAFANA_AUTH" "$GRAFANA/api/datasources/uid/prometheus" | jq_get 'd["type"]')"
# Every panel query must return data or at worst nothing, never an error.
errors="$(curl -s -u "$GRAFANA_AUTH" "$GRAFANA/api/dashboards/uid/loggate" | python3 -c '
import json, sys, urllib.parse, urllib.request, base64, os
d = json.load(sys.stdin)["dashboard"]
auth = base64.b64encode(os.environ["GRAFANA_AUTH"].encode()).decode()
bad = []
for panel in d["panels"]:
    for target in panel.get("targets", []):
        expr = target["expr"].replace("$namespace", ".*").replace("$__rate_interval", "2m").replace("[24h]", "[1h]")
        url = os.environ["PROMETHEUS"] + "/api/v1/query?" + urllib.parse.urlencode({"query": expr})
        body = json.load(urllib.request.urlopen(url))
        if body.get("status") != "success":
            bad.append(panel["title"])
print(",".join(bad) or "none")
' 2>&1)"
check "and every panel's query is valid PromQL" "none" "$errors"

echo
echo "an operator can switch pod patterns off"
CONTEXT="${KUBE_CONTEXT:-k3d-loggate}"
NAMESPACE="${APP_NS:-loggate}"
set_pattern() {
  kubectl --context "$CONTEXT" set env deploy/loggate -n "$NAMESPACE" "PODS_ALLOW_PATTERN=$1" >/dev/null 2>&1
  kubectl --context "$CONTEXT" rollout status deploy/loggate -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1
}
trap 'set_pattern true' EXIT
set_pattern false
login alice "$JAR" >/dev/null
check "the page is told, so it offers no pattern field" "False" \
  "$(body "$(api "$JAR" GET /api/me)" | jq_get 'd["podPatternAllowed"]')"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"podPattern\":\"platform-api-*\",\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "and a request carrying one anyway is refused" "400" "$(status "$r")"
r="$(api "$JAR" POST /api/exports/estimate \
  "{\"namespaces\":[\"platform-dev\"],\"pods\":[\"$POD\"],\"from\":\"$FROM\",\"to\":\"$TO\"}")"
check "while picking pods from the list still works" "200" "$(status "$r")"
set_pattern true
trap - EXIT
login alice "$JAR" >/dev/null
check "and patterns are back when switched on again" "True" \
  "$(body "$(api "$JAR" GET /api/me)" | jq_get 'd["podPatternAllowed"]')"

summary
