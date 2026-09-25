--liquibase formatted sql

--changeset loggate:0008-job-format
-- What the export's files hold: JSON lines with timestamps and labels, or the
-- raw log lines alone. Chosen per export and stored with it, so a window that
-- is retried, the manifest and the download script all agree on what the
-- files are. Every export before this was JSON.
ALTER TABLE export_job ADD COLUMN format text NOT NULL DEFAULT 'JSON';
