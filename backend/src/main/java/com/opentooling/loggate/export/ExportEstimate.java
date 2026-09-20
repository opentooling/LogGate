package com.opentooling.loggate.export;

import java.time.Instant;
import java.util.Map;

/**
 * What an export would cost, worked out before any log data is fetched.
 *
 * @param selector the generated LogQL selector
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 * @param estimatedBytes bytes Loki will read for the selector, before any line
 *     filter. This is what quota is judged on, because a filtered export still
 *     makes Loki read the whole stream
 * @param filteredBytes what the export is likely to write once the line filter
 *     is applied, extrapolated from a sample; null when there is no filter or
 *     the sample held nothing to judge by
 * @param bytesByNamespace the same, broken down per namespace
 * @param windowSeconds how long each extraction window covers
 * @param windowCount how many windows the export would run
 */
public record ExportEstimate(
    String selector,
    Instant from,
    Instant to,
    long estimatedBytes,
    Long filteredBytes,
    Map<String, Long> bytesByNamespace,
    long windowSeconds,
    int windowCount) {}
