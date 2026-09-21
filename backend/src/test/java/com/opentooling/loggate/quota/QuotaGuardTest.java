package com.opentooling.loggate.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.jobs.ExportJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuotaGuardTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final long GB = 1024L * 1024 * 1024;

  private final ExportJobRepository jobs = mock(ExportJobRepository.class);

  private QuotaGuard guard() {
    return guard(TestProperties.quotas());
  }

  private static final java.time.Clock CLOCK =
      java.time.Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), java.time.ZoneOffset.UTC);

  private QuotaGuard guard(LogGateProperties.Quotas quotas) {
    return new QuotaGuard(jobs, TestProperties.withQuotas(quotas), CLOCK);
  }

  /** Defaults with one limit varied, so a test names only what it is about. */
  private static LogGateProperties.Quotas quotas(Duration maxRange, long maxEstimatedBytes) {
    return new LogGateProperties.Quotas(
        maxRange, maxEstimatedBytes, 2, 10, 1.25, 64L * 1024 * 1024, 500 * GB, Duration.ofHours(24));
  }

  private static List<BudgetHolder> teams(String... names) {
    return java.util.Arrays.stream(names).map(BudgetHolder::team).toList();
  }

  private static ExportRequest request(Duration range) {
    return new ExportRequest(List.of("platform-dev"), null, null, null, FROM, FROM.plus(range));
  }

  private static ExportEstimate estimate(long bytes) {
    return new ExportEstimate("{}", FROM, FROM.plusSeconds(3600), bytes, null, Map.of(), 900, 4);
  }

  @Test
  void admitsAReasonableExport() {
    QuotaDecision decision = guard().admit("alice", teams("platform"), request(Duration.ofHours(6)), estimate(GB));

    assertThat(decision.admitted()).isTrue();
    assertThat(decision.reason()).isNull();
  }

  @Test
  void capsTheJobAboveItsEstimate() {
    // The range keeps receiving logs while the export runs, so a cap set at
    // exactly the estimate would fail honest exports near the end.
    QuotaDecision decision = guard().admit("alice", teams("platform"), request(Duration.ofHours(6)), estimate(GB));

    assertThat(decision.byteLimit()).isEqualTo((long) Math.ceil(GB * 1.25));
  }

  @Test
  void neverCapsBelowTheFloor() {
    QuotaDecision decision = guard().admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(1024));

    assertThat(decision.byteLimit()).isEqualTo(64L * 1024 * 1024);
  }

  @Test
  void refusesARangeLongerThanAllowed() {
    QuotaDecision decision = guard().admit("alice", teams("platform"), request(Duration.ofDays(30)), estimate(GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("30 days").contains("2 days");
  }

  @Test
  void refusesAnExportLargerThanAllowed() {
    QuotaDecision decision =
        guard().admit("alice", teams("platform"), request(Duration.ofHours(6)), estimate(200 * GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("200.0 GB").contains("50.0 GB");
  }

  @Test
  void refusesWhenTheCallerAlreadyHasTooManyRunning() {
    when(jobs.activeJobsFor("alice")).thenReturn(2);

    QuotaDecision decision = guard().admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("you already have 2 exports running");
  }

  @Test
  void refusesWhenThePlatformIsAlreadyBusy() {
    when(jobs.activeJobsFor("alice")).thenReturn(0);
    when(jobs.activeJobs()).thenReturn(10);

    QuotaDecision decision = guard().admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("across the platform");
  }

  @Test
  void refusesOnceATeamHasSpentItsDailyBudget() {
    // The limit exists to bound how much load one team can put on Loki in a
    // day, however many people are asking for it.
    when(jobs.bytesExportedByTeamSince(org.mockito.ArgumentMatchers.eq("platform"), any()))
        .thenReturn(499 * GB);

    QuotaDecision decision =
        guard().admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(5 * GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason())
        .contains("platform has exported")
        .contains("499.0 GB")
        .contains("500.0 GB");
  }

  @Test
  void admitsWhileTheTeamStillHasRoom() {
    when(jobs.bytesExportedByTeamSince(org.mockito.ArgumentMatchers.eq("platform"), any()))
        .thenReturn(100 * GB);

    assertThat(
            guard()
                .admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(5 * GB))
                .admitted())
        .isTrue();
  }

  @Test
  void measuresTheBudgetOverARollingWindowEndingNow() {
    // Rolling rather than calendar: no midnight cliff, and no question about
    // whose midnight.
    guard().admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(GB));

    var since = org.mockito.ArgumentCaptor.forClass(Instant.class);
    org.mockito.Mockito.verify(jobs)
        .bytesExportedByTeamSince(org.mockito.ArgumentMatchers.eq("platform"), since.capture());
    assertThat(since.getValue()).isEqualTo(Instant.parse("2026-09-19T12:00:00Z"));
  }

  @Test
  void chargesAMultiTeamExportToEveryTeamItTouches() {
    // A team that took part in pulling the data caused all of it; splitting
    // the cost would let one export slip under every budget it touches.
    when(jobs.bytesExportedByTeamSince(org.mockito.ArgumentMatchers.eq("platform"), any()))
        .thenReturn(0L);
    when(jobs.bytesExportedByTeamSince(org.mockito.ArgumentMatchers.eq("payments"), any()))
        .thenReturn(499 * GB);

    QuotaDecision decision =
        guard()
            .admit(
                "carol",
                teams("platform", "payments"),
                request(Duration.ofHours(1)),
                estimate(5 * GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("payments has exported");
  }

  @Test
  void switchesTheBudgetOffWhenItIsNotSet() {
    var noBudget =
        new LogGateProperties.Quotas(
            Duration.ofDays(2), 50 * GB, 2, 10, 1.25, 64L * 1024 * 1024, 0, Duration.ofHours(24));
    when(jobs.bytesExportedByTeamSince(any(), any())).thenReturn(Long.MAX_VALUE);

    assertThat(
            guard(noBudget)
                .admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(GB))
                .admitted())
        .isTrue();
  }

  @Test
  void describesSmallExportsInMegabytes() {
    QuotaDecision decision =
        guard(
                quotas(Duration.ofDays(2), 1024 * 1024))
            .admit("alice", teams("platform"), request(Duration.ofHours(1)), estimate(5 * 1024 * 1024));

    assertThat(decision.reason()).contains("5.0 MB").contains("1.0 MB");
  }

  @Test
  void describesShortRangesInHours() {
    QuotaDecision decision =
        guard(
                quotas(Duration.ofHours(6), 50 * GB))
            .admit("alice", teams("platform"), request(Duration.ofHours(12)), estimate(GB));

    assertThat(decision.reason()).contains("12 hours").contains("6 hours");
  }

  @Test
  void reportsTheLimitsAndWhatIsSpentAgainstThem() {
    when(jobs.activeJobsFor("alice")).thenReturn(1);
    when(jobs.activeJobs()).thenReturn(4);
    when(jobs.bytesExportedByTeamSince("platform", Instant.parse("2026-09-19T12:00:00Z")))
        .thenReturn(120 * GB);
    when(jobs.bytesExportedByTeamSince("payments", Instant.parse("2026-09-19T12:00:00Z")))
        .thenReturn(0L);

    QuotaReport report = guard().report("alice", teams("payments", "platform", "platform"));

    assertThat(report.maxRangeSeconds()).isEqualTo(Duration.ofDays(2).toSeconds());
    assertThat(report.maxEstimatedBytes()).isEqualTo(50 * GB);
    assertThat(report.concurrentPerUser()).isEqualTo(2);
    assertThat(report.yourActiveExports()).isEqualTo(1);
    assertThat(report.concurrentGlobal()).isEqualTo(10);
    assertThat(report.activeExports()).isEqualTo(4);
    assertThat(report.budgetWindowSeconds()).isEqualTo(Duration.ofHours(24).toSeconds());
    assertThat(report.retentionSeconds()).isEqualTo(Duration.ofHours(48).toSeconds());
    // Deduplicated and ordered, so the same team named by two namespaces is
    // one line rather than two.
    assertThat(report.budgets())
        .containsExactly(
            new QuotaReport.Budget("payments", "payments", 0, 500 * GB),
            new QuotaReport.Budget("platform", "platform", 120 * GB, 500 * GB));
  }

  @Test
  void reportsNoBudgetWhenTheBudgetIsSwitchedOff() {
    LogGateProperties.Quotas off =
        new LogGateProperties.Quotas(
            Duration.ofDays(2), 50 * GB, 2, 10, 1.25, 64L * 1024 * 1024, 0, Duration.ofHours(24));

    QuotaReport report = guard(off).report("alice", teams("platform"));

    assertThat(report.budgets())
        .containsExactly(new QuotaReport.Budget("platform", "platform", 0, 0));
    // A budget that is off is not a budget of zero that everyone has spent, so
    // the repository is never asked.
    verify(jobs, never()).bytesExportedByTeamSince(any(), any());
  }

  @Test
  void chargesAPersonInOpenModeAndNamesThemRatherThanTheirSubject() {
    // Open mode has no teams, so the person is charged. The refusal speaks to
    // them by name, not by the identifier spending is recorded under.
    when(jobs.bytesExportedByTeamSince(eq("user:carol-subject"), any())).thenReturn(499 * GB);

    QuotaDecision decision =
        guard()
            .admit(
                "carol-subject",
                List.of(BudgetHolder.person("carol-subject", "carol")),
                request(Duration.ofHours(1)),
                estimate(2 * GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).startsWith("carol has exported").doesNotContain("user:");
  }
}
