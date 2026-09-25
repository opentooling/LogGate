--liquibase formatted sql

--changeset loggate:0009-log-bytes
-- The size of the log lines an export has read, as Loki counts them: the unit
-- the estimate is in, and so the one the byte cap and the team budget are held
-- to. bytes_written stays the size of what was written, which for JSON lines
-- includes each entry's labels and timestamp and runs two to three times larger.
ALTER TABLE export_job ADD COLUMN log_bytes bigint NOT NULL DEFAULT 0;
ALTER TABLE export_window ADD COLUMN log_bytes bigint NOT NULL DEFAULT 0;
-- Exports from before this counted what they wrote. That stands in for what
-- they read, over-counting them against the budget until they age out of it.
UPDATE export_job SET log_bytes = bytes_written;
UPDATE export_window SET log_bytes = bytes_written;
