package com.opentooling.loggate.export;

import com.opentooling.loggate.loki.LokiClient;
import com.opentooling.loggate.loki.VolumeEstimate;

/**
 * Sizes an export before it runs.
 *
 * <p>Loki's index volume API reports bytes for a selector without reading any
 * log data, which is what turns quota enforcement from a guess into arithmetic
 * and lets a user be told "this is 41 GB across 312 streams" before they commit
 * to it. It also sets the window duration, so the plan follows the data rather
 * than a fixed guess.
 */
public class ExportEstimator {

  private final LokiClient loki;
  private final WindowPlanner planner;

  public ExportEstimator(LokiClient loki, WindowPlanner planner) {
    this.loki = loki;
    this.planner = planner;
  }

  /** Estimates {@code request}, which must already have been authorized. */
  public ExportEstimate estimate(ExportRequest request) {
    // Sized on the stream selector alone: a line filter reduces what is
    // written, but Loki still reads the streams, so the honest number to
    // quota against is the unfiltered one.
    String streamSelector = SelectorBuilder.buildStreamSelector(request);
    VolumeEstimate volume = loki.volume(streamSelector, request.from(), request.to());

    WindowPlan plan = planner.plan(request.from(), request.to(), volume.totalBytes());
    return new ExportEstimate(
        SelectorBuilder.build(request),
        request.from(),
        request.to(),
        volume.totalBytes(),
        volume.bytesByNamespace(),
        plan.windowDuration().toSeconds(),
        plan.windowCount());
  }
}
