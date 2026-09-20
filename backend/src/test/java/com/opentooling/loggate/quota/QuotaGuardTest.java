package com.opentooling.loggate.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

  private QuotaGuard guard(LogGateProperties.Quotas quotas) {
    return new QuotaGuard(jobs, TestProperties.withQuotas(quotas));
  }

  private static ExportRequest request(Duration range) {
    return new ExportRequest(List.of("platform-dev"), null, null, null, FROM, FROM.plus(range));
  }

  private static ExportEstimate estimate(long bytes) {
    return new ExportEstimate("{}", FROM, FROM.plusSeconds(3600), bytes, Map.of(), 900, 4);
  }

  @Test
  void admitsAReasonableExport() {
    QuotaDecision decision = guard().admit("alice", request(Duration.ofHours(6)), estimate(GB));

    assertThat(decision.admitted()).isTrue();
    assertThat(decision.reason()).isNull();
  }

  @Test
  void capsTheJobAboveItsEstimate() {
    // The range keeps receiving logs while the export runs, so a cap set at
    // exactly the estimate would fail honest exports near the end.
    QuotaDecision decision = guard().admit("alice", request(Duration.ofHours(6)), estimate(GB));

    assertThat(decision.byteLimit()).isEqualTo((long) Math.ceil(GB * 1.25));
  }

  @Test
  void neverCapsBelowTheFloor() {
    QuotaDecision decision = guard().admit("alice", request(Duration.ofHours(1)), estimate(1024));

    assertThat(decision.byteLimit()).isEqualTo(64L * 1024 * 1024);
  }

  @Test
  void refusesARangeLongerThanAllowed() {
    QuotaDecision decision = guard().admit("alice", request(Duration.ofDays(30)), estimate(GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("30 days").contains("2 days");
  }

  @Test
  void refusesAnExportLargerThanAllowed() {
    QuotaDecision decision =
        guard().admit("alice", request(Duration.ofHours(6)), estimate(200 * GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("200.0 GB").contains("50.0 GB");
  }

  @Test
  void refusesWhenTheCallerAlreadyHasTooManyRunning() {
    when(jobs.activeJobsFor("alice")).thenReturn(2);

    QuotaDecision decision = guard().admit("alice", request(Duration.ofHours(1)), estimate(GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("you already have 2 exports running");
  }

  @Test
  void refusesWhenThePlatformIsAlreadyBusy() {
    when(jobs.activeJobsFor("alice")).thenReturn(0);
    when(jobs.activeJobs()).thenReturn(10);

    QuotaDecision decision = guard().admit("alice", request(Duration.ofHours(1)), estimate(GB));

    assertThat(decision.admitted()).isFalse();
    assertThat(decision.reason()).contains("across the platform");
  }

  @Test
  void describesSmallExportsInMegabytes() {
    QuotaDecision decision =
        guard(
                new LogGateProperties.Quotas(
                    Duration.ofDays(2), 1024 * 1024, 2, 10, 1.25, 64L * 1024 * 1024))
            .admit("alice", request(Duration.ofHours(1)), estimate(5 * 1024 * 1024));

    assertThat(decision.reason()).contains("5.0 MB").contains("1.0 MB");
  }

  @Test
  void describesShortRangesInHours() {
    QuotaDecision decision =
        guard(
                new LogGateProperties.Quotas(
                    Duration.ofHours(6), 50 * GB, 2, 10, 1.25, 64L * 1024 * 1024))
            .admit("alice", request(Duration.ofHours(12)), estimate(GB));

    assertThat(decision.reason()).contains("12 hours").contains("6 hours");
  }
}
