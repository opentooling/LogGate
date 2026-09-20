package com.opentooling.loggate.jobs;

import java.util.Set;

/**
 * Where an export is in its life.
 *
 * <p>Stored by name, so entries must not be renamed.
 */
public enum JobState {
  /** Accepted and waiting to be planned. */
  QUEUED,
  /** Windows exist; workers may claim them. */
  PLANNED,
  /** At least one window has been claimed. */
  RUNNING,
  /** Every window finished; artifacts are being assembled. */
  FINALIZING,
  /** Artifacts are available. */
  READY,
  /** Artifacts have been swept. */
  EXPIRED,
  /** Stopped by a user or an operator. */
  CANCELLED,
  /** Stopped by a quota, a deadline, or an upstream failure. */
  FAILED;

  private static final Set<JobState> ACTIVE = Set.of(QUEUED, PLANNED, RUNNING, FINALIZING);

  /** Whether the job still counts against concurrency quotas. */
  public boolean isActive() {
    return ACTIVE.contains(this);
  }

  /** Names of the states that count as active, for use in SQL. */
  public static Set<String> activeNames() {
    return ACTIVE.stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
}
