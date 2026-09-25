--liquibase formatted sql

--changeset loggate:0007-job-pods
-- The pods an export was restricted to, when they were picked from a list
-- rather than matched by pattern. Kept for the audit record: the selector says
-- the same thing, but as a regex, and "which pods did this take" should not
-- need one to be read. Empty when no pods were picked.
ALTER TABLE export_job ADD COLUMN pods text[] NOT NULL DEFAULT '{}';
