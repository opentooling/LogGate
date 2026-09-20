package com.opentooling.loggate.loki;

import java.util.Map;

/**
 * One log line as Loki returned it.
 *
 * @param timestampNanos Loki's nanosecond timestamp, kept as the raw value
 *     rather than an Instant because paging compares it exactly and a
 *     round-trip through a coarser type would break de-duplication
 * @param line the log line
 * @param labels the stream's labels
 */
public record LogEntry(long timestampNanos, String line, Map<String, String> labels) {

  /**
   * Identity of an entry within one nanosecond, used to skip entries already
   * returned by the previous page. Two entries from the same stream with the
   * same line and the same timestamp are indistinguishable to Loki's API, so
   * they are indistinguishable here too.
   */
  public EntryKey key() {
    return new EntryKey(labels, line);
  }

  /** @param labels the stream's labels
   *  @param line the log line */
  public record EntryKey(Map<String, String> labels, String line) {}
}
