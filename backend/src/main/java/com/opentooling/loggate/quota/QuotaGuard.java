package com.opentooling.loggate.quota;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.jobs.ExportJobRepository;
import java.time.Duration;

/**
 * Decides whether an export may run.
 *
 * <p>The limits are layered because they fail differently. A range limit stops
 * a typo becoming a week-long job. A size limit stops a correct request being
 * ruinous. Concurrency limits stop several reasonable jobs adding up to an
 * unreasonable load on Loki. Each is cheap to check and easy to explain, which
 * matters more than sophistication when someone is told no.
 */
public class QuotaGuard {

  private final ExportJobRepository jobs;
  private final LogGateProperties.Quotas quotas;

  public QuotaGuard(ExportJobRepository jobs, LogGateProperties properties) {
    this.jobs = jobs;
    this.quotas = properties.quotas();
  }

  /** Checks {@code request} before it becomes a job. */
  public QuotaDecision admit(String subject, ExportRequest request, ExportEstimate estimate) {
    Duration range = request.duration();
    if (range.compareTo(quotas.maxRange()) > 0) {
      return QuotaDecision.refused(
          "the range of %s is longer than the %s allowed for one export"
              .formatted(humanise(range), humanise(quotas.maxRange())));
    }
    if (estimate.estimatedBytes() > quotas.maxEstimatedBytes()) {
      return QuotaDecision.refused(
          "the export is estimated at %s, over the %s allowed"
              .formatted(bytes(estimate.estimatedBytes()), bytes(quotas.maxEstimatedBytes())));
    }
    int mine = jobs.activeJobsFor(subject);
    if (mine >= quotas.concurrentPerUser()) {
      return QuotaDecision.refused(
          "you already have %d exports running, which is the limit; wait for one to finish"
              .formatted(mine));
    }
    int all = jobs.activeJobs();
    if (all >= quotas.concurrentGlobal()) {
      return QuotaDecision.refused(
          "%d exports are running across the platform, which is the limit; try again shortly"
              .formatted(all));
    }
    return QuotaDecision.admitted(byteLimitFor(estimate.estimatedBytes()));
  }

  /**
   * The cap a job runs under.
   *
   * <p>Headroom above the estimate, because the estimate is taken before the
   * export runs and the range keeps receiving logs while it does. Without it,
   * a perfectly reasonable export of a live namespace would fail near the end
   * for having been slightly busier than predicted.
   */
  private long byteLimitFor(long estimatedBytes) {
    long withHeadroom = (long) Math.ceil(estimatedBytes * quotas.byteLimitHeadroom());
    return Math.max(withHeadroom, quotas.minimumByteLimit());
  }

  private static String humanise(Duration duration) {
    long hours = duration.toHours();
    return hours >= 48 ? (hours / 24) + " days" : hours + " hours";
  }

  private static String bytes(long value) {
    if (value >= 1L << 30) {
      return "%.1f GB".formatted(value / (double) (1L << 30));
    }
    return "%.1f MB".formatted(value / (double) (1L << 20));
  }
}
