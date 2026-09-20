package com.opentooling.loggate.observability;

import com.opentooling.loggate.jobs.ExportJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Queue depth and in-flight work, sampled rather than counted.
 *
 * <p>These answer "is it keeping up", which counters cannot: a rising queue
 * with a healthy completion rate is a capacity problem, while a static queue
 * with no completions is a stuck one.
 */
public class QueueGauges {

  public QueueGauges(MeterRegistry registry, ExportJobRepository jobs) {
    Gauge.builder("loggate.jobs.active", jobs, ExportJobRepository::activeJobs)
        .description("Exports not yet in a terminal state")
        .register(registry);
    Gauge.builder("loggate.windows.pending", jobs, ExportJobRepository::pendingWindows)
        .description("Windows waiting to be claimed")
        .register(registry);
    Gauge.builder("loggate.windows.running", jobs, ExportJobRepository::runningWindows)
        .description("Windows currently held under a live lease")
        .register(registry);
  }
}
