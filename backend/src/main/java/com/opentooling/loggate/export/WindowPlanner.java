package com.opentooling.loggate.export;

import com.opentooling.loggate.config.LogGateProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Divides an export's range into windows sized from the volume estimate, so
 * each window produces roughly a target number of bytes.
 *
 * <p>Window size is a real trade-off rather than a tuning knob. Too small and
 * the job pays per-query overhead thousands of times, and parts fall below the
 * 5 MiB minimum that lets object storage concatenate them server-side. Too
 * large and progress becomes coarse, a retry re-fetches more, and a window
 * stops fitting comfortably in flight.
 */
public class WindowPlanner {

  private final long targetBytesPerWindow;
  private final Duration minWindow;
  private final Duration maxWindow;
  private final int maxWindows;

  public WindowPlanner(LogGateProperties properties) {
    LogGateProperties.Windows windows = properties.windows();
    this.targetBytesPerWindow = windows.targetBytes();
    this.minWindow = windows.minDuration();
    this.maxWindow = windows.maxDuration();
    this.maxWindows = windows.maxCount();
  }

  /** Plans {@code [from, to)} for an export estimated at {@code estimatedBytes}. */
  public WindowPlan plan(Instant from, Instant to, long estimatedBytes) {
    if (!to.isAfter(from)) {
      throw new IllegalArgumentException("export range must end after it starts");
    }
    Duration total = Duration.between(from, to);
    Duration window = windowDuration(total, estimatedBytes);

    long windowSeconds = window.toSeconds();
    long totalSeconds = total.toSeconds();
    long count = (totalSeconds + windowSeconds - 1) / windowSeconds;
    if (count > maxWindows) {
      // Refuse rather than quietly producing a plan nothing will finish.
      throw new IllegalArgumentException(
          "export would need %d windows, more than the limit of %d; narrow the range or raise loggate.windows.max-count"
              .formatted(count, maxWindows));
    }

    List<ExportWindow> windows = new ArrayList<>((int) count);
    Instant cursor = from;
    for (int index = 0; cursor.isBefore(to); index++) {
      Instant end = cursor.plus(window);
      if (end.isAfter(to)) {
        end = to;
      }
      windows.add(new ExportWindow(index, cursor, end));
      cursor = end;
    }
    return new WindowPlan(List.copyOf(windows), window, estimatedBytes);
  }

  private Duration windowDuration(Duration total, long estimatedBytes) {
    if (estimatedBytes <= 0) {
      // Nothing to size against: one big window rather than thousands of empty
      // small ones.
      return clamp(maxWindow, total);
    }
    long totalSeconds = Math.max(1, total.toSeconds());
    // seconds = targetBytes / (estimatedBytes / totalSeconds), without losing
    // the ratio to integer division.
    double bytesPerSecond = (double) estimatedBytes / totalSeconds;
    long seconds = (long) Math.ceil(targetBytesPerWindow / Math.max(bytesPerSecond, 1e-9));
    return clamp(Duration.ofSeconds(Math.max(seconds, 1)), total);
  }

  private Duration clamp(Duration candidate, Duration total) {
    Duration bounded = candidate;
    if (bounded.compareTo(minWindow) < 0) {
      bounded = minWindow;
    }
    if (bounded.compareTo(maxWindow) > 0) {
      bounded = maxWindow;
    }
    // A window longer than the export itself just means a single window.
    return bounded.compareTo(total) > 0 ? total : bounded;
  }
}
