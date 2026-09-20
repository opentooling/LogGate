--liquibase formatted sql

--changeset loggate:0003-artifact-crc
-- CRC32 of the stored bytes, captured when the part is written.
--
-- A ZIP entry written with STORE must declare its CRC before its data, and the
-- parts are already gzipped, so re-deflating them into the archive would burn
-- CPU to make them very slightly larger. Recording the CRC once at write time
-- lets the archive be streamed without reading every part twice.
ALTER TABLE export_artifact ADD COLUMN crc32 bigint;
