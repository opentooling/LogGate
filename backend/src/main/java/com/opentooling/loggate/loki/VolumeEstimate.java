package com.opentooling.loggate.loki;

import java.util.Map;

/**
 * Bytes per namespace for a selector and time range, from Loki's index volume
 * API. This is what makes quota enforcement an arithmetic decision instead of a
 * guess, and it is read before any data is fetched.
 *
 * @param bytesByNamespace bytes per namespace
 */
public record VolumeEstimate(Map<String, Long> bytesByNamespace) {

  /** Total bytes across every namespace in the estimate. */
  public long totalBytes() {
    return bytesByNamespace.values().stream().mapToLong(Long::longValue).sum();
  }

  /** An estimate of nothing, used when the range holds no data at all. */
  public static VolumeEstimate empty() {
    return new VolumeEstimate(Map.of());
  }
}
