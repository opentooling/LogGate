package com.opentooling.loggate.export;

import java.time.Duration;
import java.util.List;

/**
 * How an export's time range is divided into windows.
 *
 * @param windows the windows, in chronological order
 * @param windowDuration the duration each window covers
 * @param estimatedBytes the sizing the plan was derived from
 */
public record WindowPlan(List<ExportWindow> windows, Duration windowDuration, long estimatedBytes) {

  /** How many windows the export will run. */
  public int windowCount() {
    return windows.size();
  }
}
