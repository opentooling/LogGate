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
  label such as `cluster`.
- **Enforces quotas** before and during an export — time range, estimated bytes,
  concurrent jobs, per-team daily budget, artifact storage and retention.
- **Extracts asynchronously.** Exports are split into time windows, fetched with
  bounded parallelism, and streamed to object storage as compressed parts.
- **Delivers** presigned URLs plus a manifest for bulk downloads, or a proxied
  ZIP64 stream for a browser.
- **Audits** every submission, refusal, cancellation and denied access.
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
- **S3 / MinIO** for export artifacts
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

Creates the `loggate` k3d cluster if needed, installs Loki, Alloy, Grafana and
MinIO, builds and imports the app image, installs the chart and runs
`helm test`.

| URL                          | What                              |
| ---------------------------- | --------------------------------- |
| http://loggate.localtest.me:8088  | LogGate                      |
| http://auth.localtest.me:8088     | Keycloak (realm `loggate`)   |
| http://grafana.localtest.me:8088  | Grafana (`admin` / `loggate`)|
| http://minio.localtest.me:8088    | MinIO console                |

The cluster binds host port 8088 by default (`HOST_PORT` to change it), so it
coexists with other k3d clusters already holding port 80.

Re-running the script after a code change rebuilds and upgrades in place; pass
`SKIP_STACK=1` to leave Loki, Alloy, Grafana and MinIO untouched.

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

The settings that change how LogGate behaves:

| Setting | What it does |
| --- | --- |
| `access.mode` | `teamLabel` (default) or `open` |
| `access.openRole` | the client role required in open mode; open mode will not render without one |
| `loki.clusterLabel` | the stream label naming each log's cluster, when Loki holds several |
| `namespaces.cluster` | this cluster's name, which team-label mode pins exports to |
| `openshift.enabled`, `route.enabled` | run under `restricted-v2`, served by a Route |
| `storage.checksums` | `whenRequired` (default) for S3-compatible stores; `whenSupported` for AWS only |
| `storage.caCertificate` | a Secret with the CA to trust for the storage endpoint |

Why each works as it does, including which ONTAP version is needed and why the
SDK's default checksums are off, is in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#object-storage-compatibility).

### End-to-end checks

```bash
e2e/auth-flow.sh        # authentication and the authorization matrix
e2e/export-estimate.sh  # export sizing and the published quota
e2e/export-run.sh       # an export run to completion, with parts in MinIO
e2e/retention.sh        # download links expire; retention deletes the data
e2e/open-access.sh      # the cluster pin, and open mode: role, discovery, exact export
e2e/quota-budget.sh     # the per-team daily budget, refused and restored
deploy/local/openshift-check.sh   # OpenShift mode under restricted Pod Security, random UIDs

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

| User    | Groups                             | Team-label mode | Open mode  |
| ------- | ---------------------------------- | --------------- | ---------- |
| `alice` | `ad-platform-dev`, `log-exporters` | `platform-dev`  | everything |
| `bob`   | `ad-payments-dev`                  | `payments-dev`  | nothing    |
| `carol` | both teams, `log-exporters`        | both            | everything |
| `dave`  | none                               | nothing         | nothing    |

`log-exporters` is granted the `export-logs` client role, the way a real realm
would grant it, so open mode is exercised through group membership.

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
