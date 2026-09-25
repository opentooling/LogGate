package com.opentooling.loggate.storage;

import java.util.UUID;

/**
 * Object keys for an export's artifacts.
 *
 * <p>Derived entirely from the job and window index, never from a timestamp or
 * an attempt counter. That is what makes a retried window overwrite its own
 * part instead of adding a second copy, and it is why workers need no
 * coordination beyond the queue.
 */
public final class PartKeys {

  private PartKeys() {}

  /** Everything belonging to one job. */
  public static String jobPrefix(UUID jobId) {
    return "jobs/" + jobId + "/";
  }

  /** The part produced by one window, in the default format. */
  public static String part(UUID jobId, int windowIndex) {
    return part(jobId, windowIndex, com.opentooling.loggate.export.OutputFormat.JSON);
  }

  /** The part produced by one window, named for what it holds. */
  public static String part(
      UUID jobId, int windowIndex, com.opentooling.loggate.export.OutputFormat format) {
    // Zero-padded so a plain lexicographic listing is chronological.
    return jobPrefix(jobId) + "parts/%06d".formatted(windowIndex) + format.extension();
  }
}
