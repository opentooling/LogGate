# LogGate

[![CI](https://github.com/jugash/LogGate/actions/workflows/ci.yml/badge.svg)](https://github.com/jugash/LogGate/actions/workflows/ci.yml)

Governed bulk log export for Grafana Loki.

Grafana is where teams read their logs. LogGate is for the other case: a team
needs *two days of logs for a set of pods*, tens of gigabytes, as files they can
download — and that must happen without anyone handing out `logcli`, without
melting the Loki read path, and with a record of who took what.

## What it does

- **Authenticates** against Keycloak (OIDC).
- **Authorizes per namespace.** A namespace labelled `xyz.com/team: platform` is
  readable by the group `ad-platform-dev`; the mapping is resolved live from the
  Kubernetes API and fails closed.
- **Enforces quotas** before and during an export — time range, estimated bytes,
  concurrent jobs, per-team daily budget, artifact storage and retention.
- **Extracts asynchronously.** Exports are split into time windows, fetched with
  bounded parallelism, and streamed to object storage as compressed parts.
- **Delivers** presigned URLs plus a manifest for bulk downloads, or a proxied
  ZIP64 stream for a browser.
- **Audits** every submission, state change and download.

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

### End-to-end checks

```bash
e2e/auth-flow.sh        # authentication and the authorization matrix
e2e/export-estimate.sh  # export sizing and the published quota
e2e/export-run.sh       # an export run to completion, with parts in MinIO
e2e/retention.sh        # download links expire; retention deletes the data
e2e/quota-budget.sh     # the per-team daily budget, refused and restored

cd ui && npx playwright test   # the UI, through the real login and a real export
```

Both drive the real OIDC authorization code flow through Keycloak with a cookie
jar and assert against the deployed stack, including the cases that must be
**refused** and the audit rows they produce. The export suites need seeded
logs: run `deploy/local/seed-logs.sh`, then `e2e/wait-for-logs.sh`, which
returns once Loki can actually serve them.

### Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push to
`main` and every pull request:

| Job | What it proves |
| --- | --- |
| Build and test | `./gradlew check` — unit and Testcontainers integration tests, the 95% line and branch gate, the UI tests and build — then the Jib image |
| Helm chart | the chart lints, and `extraObjects` renders in both of its forms |
| Architecture model | `docs/architecture.calm.json` validates with the FINOS CALM CLI |
| End-to-end on k3d | the whole stack deployed to a fresh cluster by `deploy/local/deploy.sh`, then every suite above, including Playwright |

The end-to-end job runs only once the build and chart jobs pass, and on failure
keeps the pod logs, cluster events and Playwright traces as a run artifact.

### Demo users

Local only, all with password `loggate`. They exist to exercise the
authorization matrix rather than to look realistic:

| User    | Group             | May export      |
| ------- | ----------------- | --------------- |
| `alice` | `ad-platform-dev` | `platform-dev`  |
| `bob`   | `ad-payments-dev` | `payments-dev`  |
| `carol` | both              | both            |
| `dave`  | none              | nothing         |

Keycloak admin console is `admin` / `admin`.

### Log fixtures

```bash
deploy/local/seed-logs.sh              # labelled namespaces + chatty workloads
deploy/local/seed-logs.sh --remove
RATE=500 REPLICAS=4 deploy/local/seed-logs.sh   # build real volume
```

This creates `platform-dev` and `payments-dev`, labelled `xyz.com/team`, which
are the fixtures the authorization model is tested against.

### Database migrations

Schema changes are Liquibase changesets under
`backend/src/main/resources/db/changelog/changes`, applied at application
startup. Never edit a changeset that has already been applied — add a new one.
