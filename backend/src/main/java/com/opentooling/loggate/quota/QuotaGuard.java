package com.opentooling.loggate.quota;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.jobs.ExportJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
  private final Duration retention;
  private final Clock clock;

  public QuotaGuard(ExportJobRepository jobs, LogGateProperties properties, Clock clock) {
    this.jobs = jobs;
    this.quotas = properties.quotas();
    this.retention = properties.execution().retention();
    this.clock = clock;
  }

  /**
   * The same limits {@link #admit} enforces, with what is already spent against
   * them, so they can be shown before a request is made rather than only in a
   * refusal.
   *
   * @param subject the caller, for their own concurrency count
   * @param holders the budgets to report: the caller's teams, or the caller
   */
  public QuotaReport report(String subject, List<BudgetHolder> holders) {
    Instant since = clock.instant().minus(quotas.budgetWindow());
    List<QuotaReport.Budget> budgets =
        holders.stream()
            .distinct()
            .sorted(java.util.Comparator.comparing(BudgetHolder::label))
            .map(
                holder ->
                    new QuotaReport.Budget(
                        holder.id(),
                        holder.label(),
                        spentBy(holder.id(), since),
                        Math.max(quotas.dailyBytesPerTeam(), 0)))
            .toList();
    return new QuotaReport(
        quotas.maxRange().toSeconds(),
        quotas.maxEstimatedBytes(),
        quotas.concurrentPerUser(),
        jobs.activeJobsFor(subject),
        quotas.concurrentGlobal(),
        jobs.activeJobs(),
        quotas.budgetWindow().toSeconds(),
        retention.toSeconds(),
        budgets);
  }

  /** Nothing is spent when the budget is switched off, and nothing is queried. */
  private long spentBy(String holder, Instant since) {
    return quotas.dailyBytesPerTeam() <= 0 ? 0 : jobs.bytesExportedByTeamSince(holder, since);
  }

  /** Checks {@code request} before it becomes a job. */
  public QuotaDecision admit(
      String subject, List<BudgetHolder> holders, ExportRequest request, ExportEstimate estimate) {
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

    QuotaDecision overBudget = checkDailyBudget(holders, estimate.estimatedBytes());
    if (overBudget != null) {
      return overBudget;
    }

    return QuotaDecision.admitted(byteLimitFor(estimate.estimatedBytes()));
  }

  /**
   * The team's exports over a rolling day.
   *
   * <p>Rolling rather than calendar so there is no midnight cliff and no
   * question about whose midnight. A team that exports its budget in one go
   * waits for it to age out rather than until an arbitrary boundary.
   *
   * <p>An export spanning several teams counts in full against each of them. A
   * team that took part in pulling that data did cause all of it, and splitting
   * the cost would let a multi-team export slip under every budget it touches.
   *
   * @return a refusal, or null when there is room
   */
  private QuotaDecision checkDailyBudget(List<BudgetHolder> holders, long estimatedBytes) {
    if (quotas.dailyBytesPerTeam() <= 0) {
      return null;
    }
    Instant since = clock.instant().minus(quotas.budgetWindow());
    for (BudgetHolder holder : holders) {
      long used = jobs.bytesExportedByTeamSince(holder.id(), since);
      if (used + estimatedBytes > quotas.dailyBytesPerTeam()) {
        return QuotaDecision.refused(
            ("%s has exported %s in the last %s and this would add %s, over the %s allowed."
                    + " Wait for earlier exports to age out, or narrow this one.")
                .formatted(
                    holder.label(),
                    bytes(used),
                    humanise(quotas.budgetWindow()),
                    bytes(estimatedBytes),
                    bytes(quotas.dailyBytesPerTeam())));
      }
    }
    return null;
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
