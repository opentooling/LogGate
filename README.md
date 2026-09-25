# LogGate

[![CI](https://github.com/opentooling/LogGate/actions/workflows/ci.yml/badge.svg)](https://github.com/opentooling/LogGate/actions/workflows/ci.yml)

Governed bulk log export for Grafana Loki.

Grafana is where teams read their logs. LogGate is for the other case: a team
needs *two days of logs for a set of pods*, tens of gigabytes, as files they can
download — and that must happen without anyone handing out `logcli`, without
melting the Loki read path, and with a record of who took what.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/00-overview-dark.png">
  <img alt="LogGate with an export running across two namespaces and a finished one ready to download" src="docs/images/00-overview-light.png">
</picture>

**See it before you run it:** the [user guide](docs/USER-GUIDE.md) walks through
signing in, sizing an export, the quota, watching it run and downloading it, with
screenshots of each step.

## What it does

- **Authenticates** against Keycloak (OIDC).
- **Authorizes per namespace**, in one of two modes:
  - **Team ownership.** A namespace labelled `xyz.com/team: platform` is
    readable by the group `ad-platform-dev`; the mapping is resolved live from
    the Kubernetes API and fails closed.
  - **Open access.** Every namespace Loki holds, from every cluster that ships
    to it, for holders of one Keycloak client role, typically granted through a
    group. Clusters and namespaces are discovered from Loki itself, so LogGate
    needs no access to any cluster's API.
- **Tells clusters apart** when one Loki collects from several, by a stream
  label such as `cluster`, and lists a cluster's namespaces only once it is
  chosen, so opening the page never asks Loki about every cluster at once.
- **Lists pods** from any Prometheus-compatible API (kube-state-metrics'
  `kube_pod_info` by default; the metric and its labels are configurable),
  for the chosen namespaces over the chosen range, so pods are picked by name
  rather than guessed at with a pattern.
- **Enforces quotas** before and during an export — time range, estimated bytes,
  concurrent jobs, per-team daily budget, artifact storage and retention.
- **Extracts asynchronously.** Exports are split into time windows, fetched with
  bounded parallelism, and streamed to object storage as compressed parts.
- **Delivers** presigned URLs plus a manifest for bulk downloads, or a proxied
  ZIP64 stream for a browser.
- **Audits** every submission, refusal, cancellation, denied access and
  download, with an Audit page listing every download for administrators.
- **Reports on itself**: Prometheus metrics on a management port that is never
  published, a Grafana dashboard shipped with the chart, and the same picture
  on the app's own Activity page for administrators.
- **Runs on OpenShift** under the `restricted-v2` SCC, with a Route, and
  stores exports in any S3-compatible store, including NetApp ONTAP S3 and
  StorageGRID.

## Design

The architecture, and the options that were rejected, are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), with the structural model as
architecture-as-code in [docs/architecture.calm.json](docs/architecture.calm.json)
(FINOS CALM). The short version: users never write LogQL,
the unit of work is an idempotent time window, and nothing about an export is
allowed to be unbounded.

## Stack

- **Vite + React + TypeScript** UI, built into the backend jar so the app is a
  single deployable and the SPA is same-origin with its API
- **Java 25** and **Spring Boot 4.1**, built with **Gradle** (the toolchain is
  downloaded on demand, so no JDK install is required). No component scanning:
  every bean is declared in a configuration class under `config`
- **PostgreSQL 17** via `JdbcClient` with hand-written SQL; schema in
  **Liquibase** changelogs
- **S3-compatible storage** for export artifacts (Versity S3 Gateway locally)
- **Keycloak** for OIDC, **Kubernetes API** for namespace labels
- **JUnit 5** with a 95% line *and* branch coverage gate, **Testcontainers** for
  integration tests, **Playwright** for end-to-end
- **Helm** chart, verified on **k3d** locally

## Local development

Requirements: Docker or Podman, k3d, kubectl, Helm. No JDK or Gradle install
needed — the wrapper and toolchain resolver handle both.

```bash
./gradlew check          # unit + integration tests, and the coverage gate
```

Integration tests start PostgreSQL through Testcontainers, so a container
runtime must be running.

### Deploy to local Kubernetes

```bash
deploy/local/deploy.sh
```

Creates the `loggate` k3d cluster if needed, installs Loki, Alloy, Prometheus
(with kube-state-metrics), Grafana and an S3 server (Versity S3 Gateway), builds
and imports the app image,
installs the chart and runs `helm test`.

| URL                          | What                              |
| ---------------------------- | --------------------------------- |
| http://loggate.localtest.me:8088  | LogGate; `/welcome` and `/guide` are open without signing in |
| http://auth.localtest.me:8088     | Keycloak (realm `loggate`)   |
| http://grafana.localtest.me:8088  | Grafana (`admin` / `loggate`), dashboard "LogGate" |
| http://prometheus.localtest.me:8088 | Prometheus                 |
| http://s3.localtest.me:8088       | The S3 API (`loggate` / `loggate-local-dev`) |

The cluster binds host port 8088 by default (`HOST_PORT` to change it), so it
coexists with other k3d clusters already holding port 80.

Re-running the script after a code change rebuilds and upgrades in place; pass
`SKIP_STACK=1` to leave Loki, Alloy, Prometheus, Grafana and the S3 server untouched.

### Installing the published chart

After every commit to `main` that passes the whole pipeline, including the
end-to-end run, CI publishes the image and the chart to GHCR:

```bash
helm install loggate oci://ghcr.io/opentooling/charts/loggate -f my-values.yaml
helm show values oci://ghcr.io/opentooling/charts/loggate      # every setting
```

The image is `ghcr.io/opentooling/loggate`, for amd64 and arm64, tagged
`sha-<short commit>`, `sha-<full commit>`, `main` and `latest`. Chart versions
are `<major.minor from Chart.yaml>.<CI run number>`, so a plain install gets the
newest, and each chart's `appVersion`, its default image tag, is the image built
from the same commit. Pin both with `--version` and `image.tag` in production.

### Deploying to production

[`deploy/examples/values-openshift-open-access.yaml`](deploy/examples/values-openshift-open-access.yaml)
is a complete, commented production configuration: OpenShift with a Route, open
access for a Keycloak group across several clusters, an external PostgreSQL,
and NetApp ONTAP S3 behind an internal CA. It includes the Keycloak steps and
the secrets to create.

Signing out also signs out of Keycloak, which returns to the landing page,
`https://<your LogGate host>/welcome`. Add `https://<your LogGate host>/*`
to the client's **Valid post logout redirect URIs**, or Keycloak stops at an
error page instead of returning.

The settings that change how LogGate behaves:

| Setting | What it does |
| --- | --- |
| `access.mode` | `teamLabel` (default) or `open` |
| `access.openRole` | the client role required in open mode; open mode will not render without one |
| `access.adminRole` | the client role (default `loggate-admin`) that opens the Activity and Audit pages, in either mode; empty makes nobody an administrator |
| `loki.clusterLabel` | the stream label naming each log's cluster, when Loki holds several |
| `namespaces.cluster` | this cluster's name, which team-label mode pins exports to |
| `openshift.enabled`, `route.enabled` | run under `restricted-v2`, served by a Route |
| `storage.checksums` | `whenRequired` (default) for S3-compatible stores; `whenSupported` for AWS only |
| `storage.caCertificate`, `oidc.caCertificate`, `pods.caCertificate` | a Secret (`secretName`) or ConfigMap (`configMapName`) holding the CA to trust for storage, the identity provider, or the metrics store |
| `pods.metricsUrl` | a Prometheus-compatible API to list pods from; empty leaves the pod pattern as the only way to narrow by pod |
| `pods.metric`, `pods.podLabel`, `pods.namespaceLabel`, `pods.clusterLabel` | the series with one entry per pod, and its labels; `kube_pod_info`, `pod`, `namespace` and Loki's cluster label by default |
| `pods.allowPattern` | whether pods may also be matched by a glob; `false` limits narrowing to pods picked from the list, and the API refuses a pattern |
| `metrics.serviceMonitor.enabled`, `metrics.podAnnotations` | have the Prometheus Operator, or an annotation-driven Prometheus, scrape LogGate |
| `metrics.dashboard.enabled` | ship the Grafana dashboard as a ConfigMap for Grafana's sidecar |

Why each works as it does, including which ONTAP version is needed and why the
SDK's default checksums are off, is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#object-storage-compatibility).

### Metrics and dashboards

LogGate serves Prometheus metrics at `/actuator/prometheus` on port 9090, a
management port that the Service's `http` port, the Ingress and the Route
never reach, so metrics are scraped without a login and still never
published. Liveness and readiness are on the same port, and on the
application port as `/livez` and `/readyz`.

`deploy/helm/loggate/dashboards/loggate.json` is a Grafana dashboard over
those metrics: what is in flight and whether the queue drains, submissions
and refusals, outcomes and failure codes, bytes and entries written, window
and Loki page latency, Loki throttling, and the JVM. It takes its Prometheus
data source as a variable, so it imports anywhere; with
`metrics.dashboard.enabled` the chart ships it as a ConfigMap that Grafana's
dashboard sidecar loads by label. The local stack does exactly that.

Any Prometheus-compatible store works for both, and Grafana Mimir is checked
end to end by `deploy/local/mimir-check.sh`: set `pods.metricsUrl` to Mimir's
query path including its `/prometheus` prefix, for example
`http://mimir-query-frontend.mimir.svc:8080/prometheus`, and `pods.tenantId`
to the tenant the metrics were written under. Without the tenant Mimir refuses
the query, and the page falls back to the pod pattern.

The app's own **Activity** page, for holders of `access.adminRole`, answers
the same questions from PostgreSQL rather than Prometheus, so it is exact
across replicas and restarts, and works where no metrics stack is installed.

### End-to-end checks

```bash
e2e/auth-flow.sh        # authentication and the authorization matrix
e2e/export-estimate.sh  # export sizing and the published quota
e2e/export-run.sh       # an export run to completion, with its parts in storage
e2e/retention.sh        # download links expire; retention deletes the data
e2e/pods-and-metrics.sh # pods listed from kube-state-metrics, activity, the Grafana dashboard
e2e/open-access.sh      # the cluster pin, and open mode: role, discovery, exact export
e2e/quota-budget.sh     # the per-team daily budget, refused and restored
deploy/local/openshift-check.sh   # OpenShift mode under restricted Pod Security, random UIDs
deploy/local/mimir-check.sh       # pod listing and the dashboard against Grafana Mimir

cd ui && npx playwright test   # the UI, through the real login and a real export
```

Both drive the real OIDC authorization code flow through Keycloak with a cookie
jar and assert against the deployed stack, including the cases that must be
**refused** and the audit rows they produce. The export suites need seeded
logs: run `deploy/local/seed-logs.sh` and `deploy/local/seed-remote-cluster.sh`,
then `e2e/wait-for-logs.sh`, which returns once Loki can actually serve them.

### Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to
`main` and every pull request:

| Job | What it proves |
| --- | --- |
| Build and test | `./gradlew check` — unit and Testcontainers integration tests, the 95% line and branch gate, the UI tests and build — then the Jib image |
| Helm chart | the chart lints; `extraObjects` renders in both forms; the OpenShift example renders as documented; unsafe access configurations refuse to render |
| Architecture model | `docs/architecture.calm.json` validates with the FINOS CALM CLI |
| End-to-end on k3d | the whole stack deployed to a fresh cluster by `deploy/local/deploy.sh`, with a second cluster's logs, then every suite above, including Playwright and the OpenShift check |
| Publish image, Publish chart | on `main` only, after every job above passes: the multi-arch image, then the chart, pulled back to check it installs that image |

The end-to-end job runs only once the build and chart jobs pass, and on failure
keeps the pod logs, cluster events and Playwright traces as a run artifact.

### Screenshots

The images in `docs/images` are captured from a running deployment rather than
drawn, so they can be regenerated whenever the UI changes:

```bash
cd ui && npm run screenshots
```

Set `OPEN_APP_URL` to a release in open mode to capture those screens too;
`KEEP=1 deploy/local/openshift-check.sh` leaves one at
`http://ocp-loggate.localtest.me:8088`. It needs the local stack deployed and
seeded, and refuses to run if the
cluster's clock and this machine's disagree, which would otherwise produce
empty exports and nonsense durations while still passing.

### Demo users

Local only, all with password `loggate`. They exist to exercise the
authorization matrix rather than to look realistic:

| User    | Groups                                               | Team-label mode | Open mode  | Activity and Audit |
| ------- | ---------------------------------------------------- | --------------- | ---------- | ------------------ |
| `alice` | `ad-platform-dev`, `log-exporters`                   | `platform-dev`  | everything | no                 |
| `bob`   | `ad-payments-dev`                                    | `payments-dev`  | nothing    | no                 |
| `carol` | both teams, `log-exporters`, `loggate-admins`        | both            | everything | yes                |
| `dave`  | none                                                 | nothing         | nothing    | no                 |

`log-exporters` is granted the `export-logs` client role and `loggate-admins`
the `loggate-admin` role, the way a real realm would grant them, so both are
exercised through group membership.

Keycloak admin console is `admin` / `admin`.

### Log fixtures

```bash
deploy/local/seed-logs.sh              # labelled namespaces + chatty workloads
deploy/local/seed-logs.sh --remove
RATE=500 REPLICAS=4 deploy/local/seed-logs.sh   # build real volume
```

This creates `platform-dev` and `payments-dev`, labelled `xyz.com/team`, which
are the fixtures the authorization model is tested against.

```bash
deploy/local/seed-remote-cluster.sh    # a second cluster's logs, straight into Loki
```

Stands in for a cluster LogGate cannot reach: `edge-eu`, whose namespaces exist
only as labels in Loki. It includes its own `platform-dev`, which is what the
team-label mode's pin to its own cluster keeps out.

### Database migrations

Schema changes are Liquibase changesets under
`backend/src/main/resources/db/changelog/changes`, applied at application
startup. Never edit a changeset that has already been applied — add a new one.

## License

[MIT](LICENSE)
