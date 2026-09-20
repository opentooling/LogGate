package com.opentooling.loggate.jobs;

/** Why an export stopped. Recorded on the job and shown to the caller. */
public enum FailureCode {
  /** The export produced more bytes than it was admitted for. */
  BYTE_LIMIT_EXCEEDED,
  /** A window exhausted its attempts against Loki. */
  UPSTREAM_FAILED,
  /** Artifacts could not be written. */
  STORAGE_FAILED,
  /** Something unexpected. */
  INTERNAL
}
