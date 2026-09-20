--liquibase formatted sql

--changeset loggate:0005-job-teams
-- The teams an export belongs to, resolved from the namespace labels when the
-- job is admitted and stored rather than re-derived later.
--
-- Stored because the daily budget is an accounting question about the past: if
-- a namespace's team label changes, yesterday's exports still belonged to the
-- team that took them, and re-deriving would quietly rewrite history.
ALTER TABLE export_job ADD COLUMN teams text[] NOT NULL DEFAULT '{}';

--changeset loggate:0005-team-budget-index
-- The budget check sums a team's recent exports on every submission, so it
-- must not scan the table.
CREATE INDEX export_job_team_usage_idx ON export_job USING gin (teams);
CREATE INDEX export_job_created_at_idx ON export_job (created_at DESC);
