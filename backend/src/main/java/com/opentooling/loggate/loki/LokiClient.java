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
   * One page of entries in {@code [from, to)}, oldest first.
   *
   * <p>Loki's {@code start} is inclusive, so a caller paging forward by the
   * last timestamp will see the entries at that timestamp again.
   */
  QueryPage queryRange(String selector, Instant from, Instant to, int limit);
}
