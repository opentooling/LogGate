package com.opentooling.loggate.jobs;

/** Where one window is in its life. Stored by name. */
public enum WindowState {
  /** Never claimed. */
  PENDING,
  /** Claimed by a worker holding a lease. */
  CLAIMED,
  /** Extracted and written. */
  DONE,
  /** Exhausted its attempts. */
  FAILED
}
