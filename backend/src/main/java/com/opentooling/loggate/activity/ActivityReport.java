package com.opentooling.loggate.activity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * How exporting has gone across the whole installation, for the page's own
 * dashboard.
 *
 * <p>Aggregates only: no names, no namespaces, no selectors. Anyone who may
 * export can see it, and in team-label mode that includes people who may not
 * know which namespaces other teams export, so nothing here says.
 *
 * @param from start of the period reported on
 * @param to end of the period
 * @param bucketSeconds width of each point in {@code series}
 * @param now what is in flight at this moment
 * @param totals counts and volumes over the whole period
 * @param failures failed exports in the period, by failure code
 * @param durationSeconds how long finished exports took, from start to ready
 * @param series the period divided into buckets, oldest first, with every
 *     bucket present even when nothing happened in it
 */
public record ActivityReport(
    Instant from,
    Instant to,
    long bucketSeconds,
    Now now,
    Totals totals,
    Map<String, Long> failures,
    Durations durationSeconds,
    List<Point> series) {

  /**
   * @param activeExports exports not yet in a terminal state
   * @param windowsPending windows waiting for a worker
   * @param windowsRunning windows held by a worker now
   */
  public record Now(int activeExports, int windowsPending, int windowsRunning) {}

  /**
   * @param submitted exports accepted
   * @param refused submissions refused by quota
   * @param denied requests refused by authorization
   * @param ready exports that finished and were delivered, including any
   *     since expired
   * @param failed exports that failed
   * @param cancelled exports cancelled by their owner
   * @param bytesExported uncompressed bytes written by windows that completed
   * @param entriesExported log entries written by windows that completed
   */
  public record Totals(
      long submitted,
      long refused,
      long denied,
      long ready,
      long failed,
      long cancelled,
      long bytesExported,
      long entriesExported) {}

  /**
   * Percentiles of start-to-ready time, in seconds; null when nothing finished.
   *
   * @param p50 median
   * @param p95 95th percentile
   * @param max longest
   */
  public record Durations(Double p50, Double p95, Double max) {}

  /**
   * One bucket.
   *
   * @param at start of the bucket
   */
  public record Point(
      Instant at,
      long submitted,
      long refused,
      long ready,
      long failed,
      long cancelled,
      long bytesExported) {}
}
