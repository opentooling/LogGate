package com.opentooling.loggate.quota;

import java.util.List;

/**
 * The limits an export is judged against, and how much of each is already
 * spent.
 *
 * <p>Quota that is only discovered by being refused is quota that teaches
 * nothing. Publishing the same numbers the guard enforces lets someone see the
 * budget while they are choosing a range, rather than after being told no.
 *
 * @param maxRangeSeconds longest range one export may cover
 * @param maxEstimatedBytes largest single export that may be admitted
 * @param concurrentPerUser exports one person may have in flight
 * @param yourActiveExports how many the caller has in flight now
 * @param concurrentGlobal exports the platform may have in flight
 * @param activeExports how many are in flight across the platform now
 * @param budgetWindowSeconds the rolling window the team budget is measured over
 * @param retentionSeconds how long a finished export's files are kept
 * @param teams the caller's teams, with what each has spent
 */
public record QuotaReport(
    long maxRangeSeconds,
    long maxEstimatedBytes,
    int concurrentPerUser,
    int yourActiveExports,
    int concurrentGlobal,
    int activeExports,
    long budgetWindowSeconds,
    long retentionSeconds,
    List<TeamBudget> teams) {

  /**
   * One team's standing against the rolling budget.
   *
   * @param team the team label value
   * @param usedBytes what it has exported inside the window
   * @param limitBytes what it may export, or zero when the budget is off
   */
  public record TeamBudget(String team, long usedBytes, long limitBytes) {}
}
