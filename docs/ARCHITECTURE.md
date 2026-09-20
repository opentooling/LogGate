# LogGate architecture

The structural source of truth is [`architecture.calm.json`](architecture.calm.json),
a FINOS CALM model that validates against the CALM 1.0 schema. This document
explains the *why*; the model holds the shape. When the two disagree, the model
is right about structure and this document is right about intent — fix whichever
is stale.

> **Status.** LogGate is greenfield. What exists today is the control plane
> skeleton, the schema, the chart and the local stack. Everything else here is
> designed and encoded in the schema, but not yet built. Per-component state is
> in the model's `implementation-status` metadata.

## What this system is for

Teams read their logs in Grafana. That is the right tool and LogGate does not
compete with it.

The case LogGate exists for is the other one: *give me two days of logs for
these pods, as files*. Tens of gigabytes. It happens rarely, it must not melt
the Loki read path, and it must not be solved by handing out `logcli`
credentials — because that is an unbounded, unaudited, un-quotable read against
production log data.

So the design question is not "how do I get logs out of Loki". It is "how do I
make a rare, large, dangerous operation safe, bounded and accountable".

## Topology

```mermaid
flowchart LR
  eng([Exporting engineer])
  ops([Platform operator])

  subgraph LogGate
    ui[LogGate UI<br/>React SPA]
    api[Control plane<br/>Spring Boot]
    wrk[Extraction workers]
    pg[(PostgreSQL)]
  end

  s3[(Export artifact store<br/>S3 / MinIO)]
  kc[Keycloak]
  k8s[Kubernetes API]
  loki[Grafana Loki]
  alloy[Alloy]
  graf[Grafana]

  eng --> ui
  ops --> ui
  ui -->|session cookie, same origin| api
  api -->|OIDC| kc
  api -->|watch namespace labels| k8s
  api -->|jobs, windows, audit| pg
  api -->|index/volume_range| loki
  wrk -->|claim windows| pg
  wrk -->|query_range| loki
  wrk -->|gzip parts| s3
  api -->|presign, assemble, sweep| s3
  eng -.->|presigned download| s3
  alloy --> loki
  alloy --> k8s
  eng -.->|day-to-day| graf
  graf --> loki
```

Two things in that picture carry most of the design weight.

**The control plane never carries bulk data.** It plans, authorizes, meters and
mints URLs. Tens of gigabytes flow worker → object store → user, never through
the API. The one exception is the proxied ZIP convenience path, which is opt-in
and size-capped.

**Postgres is the queue.** Windows are claimed with `FOR UPDATE SKIP LOCKED`
under a heartbeat lease. There is no broker, because the job state and the work
queue are the same data, and splitting them would buy a distributed-consistency
problem in exchange for nothing.

## Authorization

A namespace labelled `xyz.com/team: platform` is readable by the group rendered
from a configurable template — `ad-{team}-{env}` giving `ad-platform-dev`.

The critical decision is *where that mapping is read from*. It is the Kubernetes
API, watched and cached — not the token, and not a static config file. Group
membership is Keycloak's to assert; the namespace-to-team mapping is the
cluster's, and it changes without anyone re-issuing a token.

Three rules fall out of that:

1. **Fail closed.** An unlabelled namespace, an unresolvable template, or a
   stale cache refuses the request. There is no configuration in which an
   unknown namespace is readable.
2. **Users never write LogQL.** The API takes a structured request — namespaces,
   pod and container selectors, a time range, an optional line filter — and
   generates the selector itself. This removes the injection class rather than
   defending against it, and it keeps the size pre-flight honest, because the
   selector that gets measured is exactly the selector that will run.
3. **Entitlement is re-checked at download.** A 48-hour artifact must not
   outlive the access that produced it.

## The export lifecycle

```mermaid
stateDiagram-v2
  [*] --> QUEUED
  QUEUED --> ESTIMATING
  ESTIMATING --> PLANNED : within quota
  ESTIMATING --> FAILED : over quota / unsizeable
  PLANNED --> RUNNING
  RUNNING --> FINALIZING : all windows done
  RUNNING --> FAILED : byte cap, deadline, upstream
  RUNNING --> CANCELLED : user or operator
  FINALIZING --> READY
  READY --> EXPIRED : TTL swept
  FAILED --> [*]
  CANCELLED --> [*]
  EXPIRED --> [*]
```

### Sizing before running

Loki's `index/volume_range` returns bytes per stream for a selector *before* any
data is fetched. That one call is the hinge of the whole design: it turns quota
enforcement from a guess into an arithmetic decision, it gives the user an
honest "this is 41 GB across 312 streams" confirmation, and it sets the window
duration. Without it the fallback is sampling a few windows and extrapolating,
which is materially worse.

### Windows

The job's time range is split into fixed windows, sized from the volume estimate
so each lands in the hundreds of megabytes. **Window `i` of job `J` always
covers exactly `[t_i, t_i+1)` and always writes the same object keys.**

That single property — deterministic, idempotent windows — is where most of the
reliability comes from:

| Property | Why it follows |
|---|---|
| Crash recovery | A lapsed lease means the window is re-claimed; the part is overwritten, not appended |
| Exactly-once artifacts | At-least-once workers are safe because retries are idempotent |
| Accurate progress | `windows_done / windows_total` is a real fraction, not an estimate |
| Free parallelism | Workers need no coordination, because windows do not overlap |
| Mid-flight quotas | Bytes are counted as written, so a cap aborts the job rather than being discovered afterwards |

### The nanosecond boundary

This is the highest-risk code in the system and deserves naming explicitly.

Paging `query_range` forward means advancing `start` to the last entry's
timestamp. Loki's `start` is **inclusive**, and several entries can share a
nanosecond. Naive paging therefore duplicates every entry at that instant, and
in high-throughput streams that is not a rare edge case — it is most pages.

The pager carries forward the identities (stream fingerprint plus line hash) of
entries at the page's maximum timestamp and skips them on the next page.
`logcli` solves the same problem the same way. It gets the heaviest test weight
of anything in the codebase: a fake Loki that returns pathological pages — all
entries sharing one nanosecond, boundaries falling exactly on page limits,
interleaved streams, 429s mid-page.

### Back-pressure

Concurrency is bounded per job and globally, and reduced additively on sustained
429s. Exports are pinned to their own Loki tenant or read pool. The intent is
that a badly-sized export degrades *itself* and never becomes an incident for
the people using Grafana.

## Artifacts and delivery

Window parts are gzip members. Concatenated gzip members are a valid gzip file,
so per-stream files are assembled server-side with S3 `UploadPartCopy` — nothing
is downloaded, re-compressed, or staged on a worker's disk. (This is why windows
are sized in the hundreds of megabytes: every copy source except the last must
be at least 5 MiB.)

Delivery is two paths, because one size genuinely does not fit:

- **Presigned URLs** plus a manifest and a generated download script — the right
  answer for tens of gigabytes, and it keeps the control plane out of the data
  path.
- **A proxied ZIP64 stream** using `STORE` rather than `DEFLATE`, since the
  members are already compressed — for the click-and-done case, size-capped into
  volumes because a single 30 GB zip is user-hostile.

The manifest records the selector, the exact time range, per-file line and byte
counts, SHA-256 checksums, and any gaps. It is what makes an export
*evidentiary* rather than merely delivered.

## Quotas

Not one limit, but layers, because they fail differently:

| Layer | Enforced |
|---|---|
| Time range, namespace and stream count | At submit |
| Estimated bytes | At submit, from `index/volume_range` |
| Hard byte cap, wall-clock deadline | Mid-extraction |
| Concurrent jobs per user / per team / global | At claim |
| Per-team daily exported volume | At submit |
| Artifact storage TTL | Swept, with an object-store lifecycle rule as backstop |

The backstop matters: a sweeper that breaks silently must not mean production
log data lives in a bucket forever.

## Failure modes

| Failure | Behaviour |
|---|---|
| Worker crash mid-window | Lease lapses, window re-claimed, part overwritten |
| Loki 429 / overload | Backoff and concurrency reduction; the job slows, it does not fail |
| Object store outage | Window fails and retries within budget; job fails cleanly after N attempts |
| User cancels | Workers observe the flag between pages; parts purged |
| Quota exceeded | Killed at the byte cap mid-flight; partial artifacts purged |
| Very recent time range | Logs still in ingesters may be absent — reported in the manifest, never silently missing |
| Group membership revoked mid-job | Download re-check refuses; artifacts expire normally |

## Audit

`audit_event` is a first-class table, not application logs. Every submission,
state transition and download is recorded with actor, selector and byte counts.

This is deliberate: these exports contain production log data, which should be
assumed to contain PII. The record of who took what must outlive the log
retention window itself, and must not be subject to the same log pipeline it is
auditing.

## Deployment

The chart ships the control plane, the workers and optionally PostgreSQL;
Keycloak, Loki and object storage are expected to exist. The control plane must
run in-cluster, because the namespace watch is a Kubernetes API client — that is
the one hard placement constraint.

Locally, `deploy/local/deploy.sh` creates a k3d cluster and installs Loki,
Alloy, Grafana and MinIO alongside it, so the full path is exercised on a
laptop. `deploy/local/seed-logs.sh` creates labelled namespaces with chatty
workloads — both realistic volume and the fixtures the authorization model is
tested against.

Images are built with Jib from compiled classes: no Dockerfile, no container
runtime in the build, and a fixed creation time so an unchanged tree produces a
byte-identical image.

## Testing

The coverage gate is 95% LINE **and** BRANCH, wired into `gradle check`, and it
was verified to fail on deliberately uncovered code rather than assumed to work.

Coverage alone would be theatre here, so the weight is placed deliberately:

- **The pager** against a fake Loki with fault injection — pathological
  nanosecond boundaries, partial pages, 429 storms, cancellation mid-page.
- **The queue** against real Postgres via Testcontainers — concurrent claims,
  lease expiry, re-claim after simulated worker death.
- **Authorization** against fixture namespaces — including the cases that must
  be refused, which matter more than the ones that succeed.
- **End to end** through real Keycloak and a real Loki in k3d, including a
  denied cross-team attempt and an export large enough to cross many windows.

## Options considered

Recorded in the model's `rejected-options` metadata, summarised here:

- **Synchronous streaming** — a ZIP straight to the browser. Rejected: a single
  connection for hours, no resume, a retry restarts tens of gigabytes, quotas
  only advisory.
- **One `logcli` Kubernetes Job per export** — rejected as the primary engine.
  It needs tens of gigabytes of staging disk (`--merge-parts` reads its own part
  files back), gives only file-count or stderr progress, cannot enforce a
  precise byte cap, and spreads credentials into per-export pods. Its
  correctness advantage is real, so it stays as a fallback engine.
- **`logcli` once per window** — keep the window planner, let Grafana own the
  pagination. Genuinely attractive, and was the standing recommendation; not
  chosen, because the decision was to own a pure-Java data path with no
  subprocess in it. The cost of that choice is precisely the nanosecond-boundary
  code above.
- **Reading chunks from Loki's object storage directly** — not rejected,
  deferred. It bypasses the queriers entirely and is the intended second tier
  for the largest exports, at the cost of coupling to the storage schema and
  missing logs still in ingesters.

The extraction engine sits behind an interface so any of these can be
substituted per size tier without touching authorization, quotas or the UI.

## Keeping this current

```bash
npx @finos/calm-cli validate -a docs/architecture.calm.json   # structural check
npx @finos/calm-cli docify   -a docs/architecture.calm.json -o docs/calm-site
```

`unique-id`s are derived from component names and are stable, so re-running the
model generation is a diff rather than a rewrite — descriptions and metadata
added by hand survive.
