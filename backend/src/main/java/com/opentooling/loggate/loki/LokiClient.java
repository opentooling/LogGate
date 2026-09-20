package com.opentooling.loggate.loki;

import java.time.Instant;

/**
 * The subset of Loki's read API that bulk export needs.
 *
 * <p>An interface rather than a class so the extraction engine can be tested
 * against pathological responses - entries sharing a nanosecond, pages that end
 * exactly on the limit, rate limiting mid-window - which is where the hard bugs
 * live.
 */
public interface LokiClient {

  /**
   * Bytes per namespace for a selector over a range, without fetching any log
   * data.
   */
  VolumeEstimate volume(String selector, Instant from, Instant to);

  /**
   * Bytes matching {@code query} over a short window ending at {@code at}.
   *
   * <p>Used to measure how selective a line filter is. The index cannot answer
   * that - it knows stream sizes, not line contents - so the only way to say
   * anything about a filter before running the export is to read a small
   * sample and extrapolate.
   *
   * @return bytes matched, or empty when the sample held no data to judge by
   */
  java.util.OptionalLong sampleBytes(String query, java.time.Instant at, java.time.Duration window);

  /**
   * One page of entries in {@code [from, to)}, oldest first.
   *
   * <p>Loki's {@code start} is inclusive, so a caller paging forward by the
   * last timestamp will see the entries at that timestamp again.
   */
  QueryPage queryRange(String selector, Instant from, Instant to, int limit);
}
