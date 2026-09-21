--liquibase formatted sql

--changeset loggate:0006-job-clusters
-- The clusters an export was taken from, when Loki holds more than one.
--
-- Stored for the same reason as the namespaces: access is re-checked against
-- them at download, and an audit of who took what is incomplete without where
-- it came from. Empty for exports made with no cluster dimension.
ALTER TABLE export_job ADD COLUMN clusters text[] NOT NULL DEFAULT '{}';

--changeset loggate:0006-teams-holds-budget-holders
-- teams now holds whoever an export is charged to: the owning teams in
-- team-label mode, or "user:<subject>" in open mode, where there are no teams.
COMMENT ON COLUMN export_job.teams IS
  'Budget holders charged for this export: team names, or user:<subject> in open access mode';

--changeset loggate:0006-namespaces-may-be-all
-- In open access mode an export may name no namespaces, meaning every one Loki
-- holds; the selector stored with the job records exactly what was queried.
-- Team-label mode still requires namespaces, enforced where requests are
-- validated, since that is the only place that knows which mode is in force.
ALTER TABLE export_job DROP CONSTRAINT export_job_namespaces_not_empty;
