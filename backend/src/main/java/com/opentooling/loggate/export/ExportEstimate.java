package com.opentooling.loggate.export;

import java.time.Instant;
import java.util.Map;

/**
 * What an export would cost, worked out before any log data is fetched.
 *
 * @param selector the generated LogQL selector
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 * @param estimatedBytes total uncompressed bytes Loki reports for the selector
 * @param bytesByNamespace the same, broken down per namespace
 * @param windowSeconds how long each extraction window covers
 * @param windowCount how many windows the export would run
 */
public record ExportEstimate(
    String selector,
    Instant from,
    Instant to,
    long estimatedBytes,
    Map<String, Long> bytesByNamespace,
    long windowSeconds,
    int windowCount) {}
