package com.opentooling.loggate.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.PostgresContainerConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The audit trail, read back as the audit page shows it. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class AuditLogTest {

  @Autowired private AuditService audit;
  @Autowired private AuditLog log;
  @Autowired private JdbcClient db;

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM audit_event").update();
    db.sql("DELETE FROM export_job").update();
  }

  private void download(AuditAction action, UUID job, Map<String, Object> detail) {
    audit.record("alice-subject", action, job, detail, "10.0.0.1");
  }

  @Test
  void readsDownloadsBackNewestFirstAPageAtATime() {
    UUID job = UUID.randomUUID();
    download(
        AuditAction.DOWNLOAD_LINKS_ISSUED,
        job,
        Map.of("name", "alice", "namespaces", List.of("platform-dev"), "clusters", List.of("k3d"),
            "bytes", 2048, "files", 3));
    download(AuditAction.DOWNLOAD_SCRIPT_ISSUED, job, Map.of("name", "alice", "files", 3));
    // Not a download: never on this page.
    audit.record("alice-subject", AuditAction.EXPORT_SUBMITTED, Map.of(), "10.0.0.1");
    // Recorded without a name: the subject stands in for it.
    download(AuditAction.ARCHIVE_DOWNLOADED, job, Map.of("bytes", 2048));

    AuditLog.Page first = log.events(AuditLog.Kind.DOWNLOADS, 2, null);
    assertThat(first.events()).extracting(AuditLog.Event::action)
        .containsExactly(AuditAction.ARCHIVE_DOWNLOADED, AuditAction.DOWNLOAD_SCRIPT_ISSUED);
    AuditLog.Event archive = first.events().getFirst();
    assertThat(archive.name()).isEqualTo("alice-subject");
    assertThat(archive.files()).isNull();
    assertThat(archive.bytes()).isEqualTo(2048);
    assertThat(archive.namespaces()).isEmpty();
    assertThat(archive.jobId()).isEqualTo(job);
    assertThat(archive.sourceIp()).isEqualTo("10.0.0.1");
    assertThat(first.next()).isNotNull();

    AuditLog.Page second = log.events(AuditLog.Kind.DOWNLOADS, 2, first.next());
    assertThat(second.events()).hasSize(1);
    AuditLog.Event links = second.events().getFirst();
    assertThat(links.name()).isEqualTo("alice");
    assertThat(links.namespaces()).containsExactly("platform-dev");
    assertThat(links.clusters()).containsExactly("k3d");
    assertThat(links.files()).isEqualTo(3);
    assertThat(links.subject()).isEqualTo("alice-subject");
    assertThat(second.next()).isNull();

    assertThat(log.totals())
        .containsEntry(AuditAction.DOWNLOAD_LINKS_ISSUED, 1L)
        .containsEntry(AuditAction.ARCHIVE_DOWNLOADED, 1L)
        .containsEntry(AuditAction.EXPORT_SUBMITTED, 1L);
  }

  @Test
  void anEmptyTrailIsAnEmptyPage() {
    assertThat(log.events(AuditLog.Kind.ALL, 10, null).events()).isEmpty();
    assertThat(log.totals()).isEmpty();
  }

  @Test
  void readsEachKindOfEventOnItsOwnOrAllTogether() {
    UUID job = UUID.randomUUID();
    audit.record("alice-subject", AuditAction.EXPORT_SUBMITTED, job,
        Map.of("name", "alice", "namespaces", List.of("shop"), "estimatedBytes", 4096), "10.0.0.1");
    audit.record("alice-subject", AuditAction.EXPORT_COMPLETED, job,
        Map.of("namespaces", List.of("shop"), "bytes", 4000, "files", 2), null);
    download(AuditAction.ARCHIVE_DOWNLOADED, job, Map.of("name", "alice", "bytes", 4000));
    audit.record("bob-subject", AuditAction.NAMESPACE_ACCESS_DENIED,
        Map.of("name", "bob", "requested", List.of("payments"), "clusters", List.of(),
            "denied", Map.of("payments", "NOT_A_MEMBER")), "10.0.0.2");
    audit.record("bob-subject", AuditAction.EXPORT_REFUSED,
        Map.of("name", "bob", "namespaces", List.of("shop"), "reason", "over the team budget"),
        "10.0.0.2");

    assertThat(log.events(AuditLog.Kind.ALL, 10, null).events()).hasSize(5);
    assertThat(log.events(AuditLog.Kind.EXPORTS, 10, null).events())
        .extracting(AuditLog.Event::action)
        .containsExactly(
            AuditAction.EXPORT_REFUSED, AuditAction.EXPORT_COMPLETED, AuditAction.EXPORT_SUBMITTED);
    assertThat(log.events(AuditLog.Kind.DOWNLOADS, 10, null).events()).hasSize(1);

    AuditLog.Event denied = log.events(AuditLog.Kind.DENIALS, 10, null).events().getFirst();
    assertThat(denied.namespaces()).containsExactly("payments");
    assertThat(denied.note()).isEqualTo("Refused payments");

    var exports = log.events(AuditLog.Kind.EXPORTS, 10, null).events();
    assertThat(exports.get(0).note()).isEqualTo("over the team budget");
    AuditLog.Event completed = exports.get(1);
    assertThat(completed.files()).isEqualTo(2);
    assertThat(completed.bytes()).isEqualTo(4000);
    assertThat(completed.sourceIp()).isNull();
    assertThat(exports.get(2).estimatedBytes()).isEqualTo(4096);
  }

  @Test
  void fillsWhatAnOlderEventLeftOutFromItsExport() {
    // Before exports were recorded against their id, the id was only in the
    // detail, and neither the name nor the namespaces were there at all.
    UUID job = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO export_job (id, requested_by, requested_by_name, requested_by_groups, state,
              namespaces, clusters, selector, time_from, time_to, estimated_bytes, byte_limit,
              window_seconds, windows_total)
            VALUES (?, 'alice-subject', 'alice', '{}', 'READY', '{shop}', '{k3d}', '{}',
              now() - interval '1 hour', now(), 1, 1, 3600, 1)
            """)
        .param(job)
        .update();
    audit.record("alice-subject", AuditAction.EXPORT_CANCELLED, Map.of("jobId", job.toString()), null);

    // A refusal recorded before names were kept names no export at all.
    audit.record("alice-subject", AuditAction.EXPORT_REFUSED, Map.of("reason", "over"), null);
    assertThat(log.events(AuditLog.Kind.EXPORTS, 10, null).events().getFirst().name())
        .isEqualTo("alice");

    AuditLog.Event cancelled = log.events(AuditLog.Kind.EXPORTS, 10, null).events().get(1);
    assertThat(cancelled.jobId()).isEqualTo(job);
    assertThat(cancelled.name()).isEqualTo("alice");
    assertThat(cancelled.namespaces()).containsExactly("shop");
    assertThat(cancelled.clusters()).containsExactly("k3d");
  }
}
