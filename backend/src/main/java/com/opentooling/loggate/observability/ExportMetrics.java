package com.opentooling.loggate.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * What an operator needs to see about exports.
 *
 * <p>Chosen for the questions someone actually asks during an incident: is the
 * queue draining, is Loki pushing back, is anything failing and why. Counters
 * carry the reason as a tag, because "exports are failing" is not actionable
 * and "exports are failing on BYTE_LIMIT_EXCEEDED" is.
 */
public class ExportMetrics {

  private final MeterRegistry registry;
  private final Timer windowDuration;
  private final Timer lokiQuery;

  public ExportMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.windowDuration =
        Timer.builder("loggate.window.duration")
            .description("Time to extract one window and write its part")
            .publishPercentileHistogram()
            .register(registry);
    this.lokiQuery =
        Timer.builder("loggate.loki.query.duration")
            .description("Time for one query_range page")
            .publishPercentileHistogram()
            .register(registry);
  }

  /** An export was accepted or refused, and why. */
  public void submission(String outcome, String reason) {
    Counter.builder("loggate.exports.submitted")
        .description("Export submissions by outcome")
        .tag("outcome", outcome)
        .tag("reason", reason)
        .register(registry)
        .increment();
  }

  /** An export reached a terminal state. */
  public void finished(String state, String failureCode) {
    Counter.builder("loggate.exports.finished")
        .description("Exports reaching a terminal state")
        .tag("state", state)
        .tag("failure", failureCode == null ? "none" : failureCode)
        .register(registry)
        .increment();
  }

  /** A window finished, with what it produced. */
  public void windowCompleted(Duration took, long entries, long uncompressedBytes) {
    windowDuration.record(took);
    registry.counter("loggate.entries.exported").increment(entries);
    registry.counter("loggate.bytes.exported").increment(uncompressedBytes);
  }

  /** A window attempt failed. */
  public void windowFailed(String reason) {
    Counter.builder("loggate.windows.failed")
        .description("Window attempts that failed")
        .tag("reason", reason)
        .register(registry)
        .increment();
  }

  /** Times one Loki page, so back-pressure is visible before it becomes a queue. */
  public <T> T timeLokiQuery(java.util.function.Supplier<T> query) {
    return lokiQuery.record(query);
  }

  /** Loki asked us to slow down. */
  public void lokiThrottled() {
    registry.counter("loggate.loki.throttled").increment();
  }
}
