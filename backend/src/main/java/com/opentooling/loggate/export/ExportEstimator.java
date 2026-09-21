package com.opentooling.loggate.export;

import com.opentooling.loggate.loki.LokiClient;
import com.opentooling.loggate.loki.VolumeEstimate;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sizes an export before it runs.
 *
 * <p>Loki's index volume API reports bytes for a selector without reading any
 * log data, which is what turns quota enforcement from a guess into arithmetic
 * and lets a user be told "this is 41 GB across 312 streams" before they commit
 * to it. It also sets the window duration, so the plan follows the data rather
 * than a fixed guess.
 *
 * <p>A line filter is a different problem. The index knows how big each stream
 * is but nothing about what is inside the lines, so a filter cannot be sized
 * from it at all. Rather than show a number that ignores the filter and let
 * people assume it is their download size, a short sample is read and the
 * filter's selectivity extrapolated.
 */
public class ExportEstimator {

  private static final Logger log = LoggerFactory.getLogger(ExportEstimator.class);

  /**
   * How much of the range to sample when judging a line filter. Long enough to
   * be representative, short enough that the sample is not itself an export.
   */
  private static final Duration SAMPLE_WINDOW = Duration.ofMinutes(5);

  private final LokiClient loki;
  private final WindowPlanner planner;
  private final String clusterLabel;

  public ExportEstimator(LokiClient loki, WindowPlanner planner) {
    this(loki, planner, "");
  }

  /** @param clusterLabel the label naming each log's cluster, or empty for none */
  public ExportEstimator(LokiClient loki, WindowPlanner planner, String clusterLabel) {
    this.loki = loki;
    this.planner = planner;
    this.clusterLabel = clusterLabel;
  }

  /** Estimates {@code request}, which must already have been authorized. */
  public ExportEstimate estimate(ExportRequest request) {
    // Sized on the stream selector alone: a line filter reduces what is
    // written, but Loki still reads the streams, so the honest number to
    // quota against is the unfiltered one.
    String streamSelector = SelectorBuilder.buildStreamSelector(request, clusterLabel);
    VolumeEstimate volume = loki.volume(streamSelector, request.from(), request.to());

    WindowPlan plan = planner.plan(request.from(), request.to(), volume.totalBytes());
    return new ExportEstimate(
        SelectorBuilder.build(request, clusterLabel),
        request.from(),
        request.to(),
        volume.totalBytes(),
        filteredBytes(request, streamSelector, volume.totalBytes()),
        volume.bytesByNamespace(),
        plan.windowDuration().toSeconds(),
        plan.windowCount());
  }

  /**
   * What the export is likely to write once the line filter is applied.
   *
   * <p>Measured by reading one short window twice - with and without the
   * filter - and applying the ratio to the whole range. It is an extrapolation
   * from a sample and is presented as one; the alternative is showing a number
   * that silently ignores the filter.
   *
   * @return null when there is no filter, or when the sample cannot support a
   *     conclusion
   */
  private Long filteredBytes(ExportRequest request, String streamSelector, long totalBytes) {
    String filter = request.lineFilter();
    if (filter == null || filter.isBlank() || totalBytes == 0) {
      return null;
    }

    Duration range = request.duration();
    Duration window = range.compareTo(SAMPLE_WINDOW) < 0 ? range : SAMPLE_WINDOW;
    // Sampled from the middle rather than either end. The end of a range that
    // reaches towards the present is the least representative part of it -
    // traffic may have just changed, and the most recent entries may not have
    // flushed - and the start has the same problem in reverse.
    Instant at = request.from().plus(range.dividedBy(2)).plus(window.dividedBy(2));
    if (at.isAfter(request.to())) {
      at = request.to();
    }

    try {
      OptionalLong sampledTotal = loki.sampleBytes(streamSelector, at, window);
      if (sampledTotal.isEmpty() || sampledTotal.getAsLong() == 0) {
        // Nothing in the sample window, so nothing can be concluded about the
        // filter from it.
        return null;
      }
      // An empty result for the filtered query is not an unknown: it means the
      // filter matched nothing in a window that definitely held data.
      OptionalLong sampledMatching =
          loki.sampleBytes(SelectorBuilder.build(request, clusterLabel), at, window);
      double selectivity =
          sampledMatching.isEmpty()
              ? 0
              : (double) sampledMatching.getAsLong() / sampledTotal.getAsLong();
      return Math.round(totalBytes * Math.min(selectivity, 1.0));
    } catch (RuntimeException e) {
      // A failed sample must not fail the estimate: the unfiltered number is
      // still the one that matters for admission.
      log.warn("Could not sample the line filter's selectivity", e);
      return null;
    }
  }
}
