--liquibase formatted sql

--changeset loggate:0001-export-job
-- One row per export request. The lifecycle is:
--   QUEUED -> ESTIMATING -> PLANNED -> RUNNING -> FINALIZING -> READY -> EXPIRED
-- with CANCELLED and FAILED reachable from any active state.
CREATE TABLE export_job (
  id                  uuid        PRIMARY KEY,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),

  -- Identity snapshot: who asked, and what they were entitled to at the time.
  -- Kept verbatim so an audit remains meaningful after group membership changes.
  requested_by        text        NOT NULL,
  requested_by_name   text,
  requested_by_groups text[]      NOT NULL,

  state               text        NOT NULL,
  failure_code        text,
  failure_detail      text,
  cancel_requested    boolean     NOT NULL DEFAULT false,

  -- Request, as validated. The selector is generated server-side; the user
  -- never supplies LogQL.
  namespaces          text[]      NOT NULL,
  pod_pattern         text,
  container_pattern   text,
  line_filter         text,
  selector            text        NOT NULL,
  time_from           timestamptz NOT NULL,
  time_to             timestamptz NOT NULL,

  -- Planning and progress.
  estimated_bytes     bigint,
  window_seconds      integer,
  windows_total       integer     NOT NULL DEFAULT 0,
  windows_done        integer     NOT NULL DEFAULT 0,
  bytes_written       bigint      NOT NULL DEFAULT 0,
  entries_written     bigint      NOT NULL DEFAULT 0,

  started_at          timestamptz,
  finished_at         timestamptz,
  expires_at          timestamptz,

  CONSTRAINT export_job_time_range CHECK (time_to > time_from),
  CONSTRAINT export_job_namespaces_not_empty CHECK (cardinality(namespaces) > 0)
);

CREATE INDEX export_job_requested_by_idx ON export_job (requested_by, created_at DESC);
CREATE INDEX export_job_state_idx ON export_job (state);
-- Drives the retention sweeper.
CREATE INDEX export_job_expires_at_idx ON export_job (expires_at) WHERE expires_at IS NOT NULL;

--changeset loggate:0001-export-window
-- The unit of work. A window is deterministic and idempotent: window i of job J
-- always covers exactly [window_from, window_to) and always writes the same
-- object keys, so a retry overwrites rather than appends.
CREATE TABLE export_window (
  job_id            uuid        NOT NULL REFERENCES export_job (id) ON DELETE CASCADE,
  idx               integer     NOT NULL,
  window_from       timestamptz NOT NULL,
  window_to         timestamptz NOT NULL,

  state             text        NOT NULL DEFAULT 'PENDING',
  attempts          integer     NOT NULL DEFAULT 0,
  last_error        text,

  -- Worker lease. A worker that dies has its windows reclaimed once the lease
  -- lapses; the heartbeat extends it while work is genuinely progressing.
  lease_owner       text,
  lease_expires_at  timestamptz,

  bytes_written     bigint      NOT NULL DEFAULT 0,
  entries_written   bigint      NOT NULL DEFAULT 0,
  completed_at      timestamptz,

  PRIMARY KEY (job_id, idx),
  CONSTRAINT export_window_time_range CHECK (window_to > window_from)
);

-- The claim query: pending or lapsed windows, oldest job first.
CREATE INDEX export_window_claimable_idx
  ON export_window (state, lease_expires_at)
  WHERE state IN ('PENDING', 'CLAIMED');

--changeset loggate:0001-export-artifact
CREATE TABLE export_artifact (
  id            uuid        PRIMARY KEY,
  job_id        uuid        NOT NULL REFERENCES export_job (id) ON DELETE CASCADE,
  kind          text        NOT NULL,
  object_key    text        NOT NULL,
  stream_labels jsonb,
  size_bytes    bigint      NOT NULL,
  sha256        text,
  created_at    timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT export_artifact_key_unique UNIQUE (job_id, object_key)
);

CREATE INDEX export_artifact_job_idx ON export_artifact (job_id, kind);

--changeset loggate:0001-audit-event
-- First-class audit trail. These exports carry production log data, so who
-- asked for what, and who downloaded it, is a record rather than a log line.
CREATE TABLE audit_event (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at         timestamptz NOT NULL DEFAULT now(),
  actor      text        NOT NULL,
  action     text        NOT NULL,
  job_id     uuid,
  detail     jsonb       NOT NULL DEFAULT '{}'::jsonb,
  source_ip  text
);

CREATE INDEX audit_event_at_idx ON audit_event (at DESC);
CREATE INDEX audit_event_job_idx ON audit_event (job_id) WHERE job_id IS NOT NULL;
CREATE INDEX audit_event_actor_idx ON audit_event (actor, at DESC);
