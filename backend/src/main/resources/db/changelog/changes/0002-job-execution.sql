--liquibase formatted sql

--changeset loggate:0002-job-byte-limit
-- The byte cap this job was admitted under, recorded per job rather than read
-- from configuration at runtime: a job must be judged against the limit that
-- applied when it was accepted, not one that changed halfway through.
ALTER TABLE export_job ADD COLUMN byte_limit bigint;

--changeset loggate:0002-active-job-index
-- Concurrency quotas count a user's and a team's running jobs on every
-- submission, so that count must not scan the table.
CREATE INDEX export_job_active_by_user_idx
  ON export_job (requested_by)
  WHERE state IN ('QUEUED', 'ESTIMATING', 'PLANNED', 'RUNNING', 'FINALIZING');

--changeset loggate:0002-window-claim-index
-- Drop the M1 index in favour of one that matches the claim query exactly:
-- pending windows, or claimed ones whose lease has lapsed, oldest first.
DROP INDEX IF EXISTS export_window_claimable_idx;
CREATE INDEX export_window_claimable_idx
  ON export_window (lease_expires_at NULLS FIRST)
  WHERE state IN ('PENDING', 'CLAIMED');
