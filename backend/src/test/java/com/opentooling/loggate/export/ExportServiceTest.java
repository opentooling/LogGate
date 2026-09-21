package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.opentooling.loggate.PostgresContainerConfig;
import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.authz.OpenAccess;
import com.opentooling.loggate.authz.TeamLabelAccess;
import com.opentooling.loggate.namespaces.LokiDirectory;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.jobs.JobState;
import com.opentooling.loggate.jobs.WindowState;
import com.opentooling.loggate.loki.FakeLokiClient;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Submitting an export: sizing, admission, planning, recording. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class ExportServiceTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final long MB = 1024L * 1024L;

  @Autowired private ExportJobRepository jobs;
  @Autowired private JdbcClient db;

  private final AuditService audit = mock(AuditService.class);
  private final FakeLokiClient loki = new FakeLokiClient();

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM export_job").update();
  }

  private static AuthenticatedUser alice() {
    return new AuthenticatedUser("alice-subject", "alice", Set.of("ad-platform-dev"));
  }

  private ExportService service() {
    return service(TestProperties.quotas());
  }

  private static final com.opentooling.loggate.namespaces.FakeNamespaceCatalog CATALOG =
      new com.opentooling.loggate.namespaces.FakeNamespaceCatalog()
          .with("platform-dev", "platform", "ad-platform-dev");

  private ExportService service(LogGateProperties.Quotas quotas) {
    return service(
        quotas,
        new TeamLabelAccess(new NamespaceAuthorizer(CATALOG), CATALOG, ""),
        "");
  }

  private ExportService service(
      LogGateProperties.Quotas quotas, NamespaceAccess access, String clusterLabel) {
    var properties = TestProperties.withQuotas(quotas);
    return new ExportService(
        new ExportEstimator(loki, new WindowPlanner(properties), clusterLabel),
        new WindowPlanner(properties),
        new QuotaGuard(jobs, properties, java.time.Clock.systemUTC()),
        jobs,
        access,
        audit,
        new com.opentooling.loggate.observability.ExportMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
  }

  private static ExportRequest request(Duration range) {
    return new ExportRequest(List.of("platform-dev"), "api-*", null, null, FROM, FROM.plus(range));
  }

  @Test
  void queuesAnAdmittedExportWithItsWindows() {
    loki.volume("platform-dev", 2048 * MB);

    var submission = service().submit(alice(), request(Duration.ofHours(8)), "10.0.0.1");

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.job().state()).isEqualTo(JobState.PLANNED);
    assertThat(submission.job().windowsTotal()).isEqualTo(8);
    assertThat(submission.job().selector()).contains("pod=~\"api\\\\-.*\"");
    assertThat(jobs.windowStates(submission.job().id())).containsEntry(WindowState.PENDING, 8);
  }

  @Test
  void capsTheJobAboveWhatSizingPredicted() {
    loki.volume("platform-dev", 2048 * MB);

    var submission = service().submit(alice(), request(Duration.ofHours(8)), "10.0.0.1");

    assertThat(submission.job().estimatedBytes()).isEqualTo(2048 * MB);
    assertThat(submission.job().byteLimit()).isGreaterThan(2048 * MB);
  }

  @Test
  void auditsTheSubmission() {
    loki.volume("platform-dev", 10 * MB);

    service().submit(alice(), request(Duration.ofHours(1)), "10.0.0.1");

    verify(audit)
        .record(eq("alice-subject"), eq(AuditAction.EXPORT_SUBMITTED), any(), eq("10.0.0.1"));
  }

  @Test
  void refusesAndAuditsAnExportOverQuota() {
    loki.volume("platform-dev", 500L * 1024 * MB);

    var submission = service().submit(alice(), request(Duration.ofHours(8)), "10.0.0.2");

    assertThat(submission.accepted()).isFalse();
    assertThat(submission.refusal()).contains("over the");
    assertThat(submission.estimate().estimatedBytes()).isEqualTo(500L * 1024 * MB);
    verify(audit).record(eq("alice-subject"), eq(AuditAction.EXPORT_REFUSED), any(), anyString());
    assertThat(jobs.activeJobs()).isZero();
  }

  @Test
  void preflightSizesAndJudgesWithoutQueueingAnything() {
    loki.volume("platform-dev", 2048 * MB);

    var preflight = service().preflight(alice(), request(Duration.ofHours(8)));

    assertThat(preflight.estimate().estimatedBytes()).isEqualTo(2048 * MB);
    assertThat(preflight.decision().admitted()).isTrue();
    assertThat(preflight.decision().byteLimit()).isGreaterThan(2048 * MB);
    // Asking what something would cost must not cost anything.
    assertThat(jobs.activeJobs()).isZero();
    verify(audit, never()).record(any(), any(), any(), any());
  }

  @Test
  void preflightGivesTheSameRefusalSubmissionWould() {
    loki.volume("platform-dev", 500L * 1024 * MB);

    var request = request(Duration.ofHours(8));
    var preflight = service().preflight(alice(), request);
    var submission = service().submit(alice(), request, "10.0.0.2");

    assertThat(preflight.decision().admitted()).isFalse();
    assertThat(preflight.decision().reason()).isEqualTo(submission.refusal());
  }

  @Test
  void showsTheCallerOnlyTheirOwnJobs() {
    loki.volume("platform-dev", 10 * MB);
    var mine = service().submit(alice(), request(Duration.ofHours(1)), "10.0.0.1");
    var theirs =
        service()
            .submit(
                new AuthenticatedUser("bob-subject", "bob", Set.of("ad-payments-dev")),
                request(Duration.ofHours(1)),
                "10.0.0.1");

    assertThat(service().listFor(alice(), 10)).extracting(j -> j.id()).containsExactly(mine.job().id());
    assertThat(service().findFor(alice(), mine.job().id())).isPresent();
    // Someone else's job is simply not found, rather than forbidden.
    assertThat(service().findFor(alice(), theirs.job().id())).isEmpty();
  }

  @Test
  void cancelsOnlyTheCallersOwnJob() {
    loki.volume("platform-dev", 10 * MB);
    var submission = service().submit(alice(), request(Duration.ofHours(1)), "10.0.0.1");
    UUID id = submission.job().id();

    assertThat(service().cancel(new AuthenticatedUser("mallory", "m", Set.of()), id, "10.0.0.9"))
        .isFalse();
    assertThat(service().cancel(alice(), id, "10.0.0.1")).isTrue();

    verify(audit).record(eq("alice-subject"), eq(AuditAction.EXPORT_CANCELLED), any(), anyString());
    verify(audit, never()).record(eq("mallory"), any(), any(), anyString());
    assertThat(jobs.isCancelRequested(id)).isTrue();
  }

  @Test
  void reportsCancellingAJobThatIsNotThereAsNoAction() {
    assertThat(service().cancel(alice(), UUID.randomUUID(), "10.0.0.1")).isFalse();
  }

  @Test
  void pinsATeamLabelExportToItsOwnClusterWhateverWasAskedFor() {
    // Kubernetes can only vouch for its own cluster's namespaces, so the
    // query, the size and the stored job all name it, even when the request
    // named none.
    loki.volume("platform-dev", 10 * MB);
    var access = new TeamLabelAccess(new NamespaceAuthorizer(CATALOG), CATALOG, "core-eu");

    var submission =
        service(TestProperties.quotas(), access, "cluster")
            .submit(alice(), request(Duration.ofHours(1)), "10.0.0.1");

    assertThat(submission.job().clusters()).containsExactly("core-eu");
    assertThat(submission.job().selector()).startsWith("{cluster=\"core-eu\", namespace=\"platform-dev\"");
    assertThat(loki.selectorsSeen().getLast()).contains("cluster=\"core-eu\"");
  }

  @Test
  void chargesThePersonInOpenModeAndExportsEveryNamespaceWhenNoneAreNamed() {
    loki.volume("checkout", 10 * MB);
    var access =
        new OpenAccess(
            new LokiDirectory(loki, "cluster", Duration.ofDays(7), Duration.ofMinutes(1), java.time.Clock.systemUTC()),
            "export-logs",
            "loggate",
            true);
    var carol =
        new AuthenticatedUser("carol-subject", "carol", Set.of(), Set.of("export-logs"));
    var everything =
        new ExportRequest(List.of(), null, null, null, FROM, FROM.plus(Duration.ofHours(1)), List.of("edge-eu"));

    var submission = service(TestProperties.quotas(), access, "cluster").submit(carol, everything, "10.0.0.3");

    assertThat(submission.accepted()).isTrue();
    assertThat(submission.job().selector()).isEqualTo("{cluster=\"edge-eu\", namespace=~\".+\"}");
    // There are no teams to charge, so carol's own budget carries it.
    assertThat(jobs.bytesExportedByTeamSince("user:carol-subject", FROM.minus(Duration.ofDays(1))))
        .isEqualTo(10 * MB);
  }
}
