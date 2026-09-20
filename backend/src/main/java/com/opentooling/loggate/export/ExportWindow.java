package com.opentooling.loggate.export;

import java.time.Instant;

/**
 * One unit of extraction work.
 *
 * <p>Window {@code index} of a job always covers exactly {@code [from, to)} and
 * always writes the same object keys. That determinism is where crash recovery,
 * exactly-once artifacts, accurate progress and free parallelism all come from:
 * a retried window overwrites its part rather than appending to it.
 *
 * @param index position in the plan, and the part's sort key
 * @param from start of the window, inclusive
 * @param to end of the window, exclusive
 */
public record ExportWindow(int index, Instant from, Instant to) {

  /** Start as Loki's nanosecond timestamp. */
  public long fromNanos() {
    return Timestamps.toNanos(from);
  }

  /** End as Loki's nanosecond timestamp. */
  public long toNanos() {
    return Timestamps.toNanos(to);
  }
}
