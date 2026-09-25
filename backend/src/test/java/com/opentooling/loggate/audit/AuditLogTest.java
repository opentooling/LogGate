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

/** The downloads, read back as the audit page shows them. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class AuditLogTest {

  @Autowired private AuditService audit;
  @Autowired private AuditLog log;
  @Autowired private JdbcClient db;

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM audit_event").update();
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

    AuditLog.Page first = log.downloads(2, null);
    assertThat(first.events()).extracting(AuditLog.Download::action)
        .containsExactly(AuditAction.ARCHIVE_DOWNLOADED, AuditAction.DOWNLOAD_SCRIPT_ISSUED);
    AuditLog.Download archive = first.events().getFirst();
    assertThat(archive.name()).isEqualTo("alice-subject");
    assertThat(archive.files()).isNull();
    assertThat(archive.bytes()).isEqualTo(2048);
    assertThat(archive.namespaces()).isEmpty();
    assertThat(archive.jobId()).isEqualTo(job);
    assertThat(archive.sourceIp()).isEqualTo("10.0.0.1");
    assertThat(first.next()).isNotNull();

    AuditLog.Page second = log.downloads(2, first.next());
    assertThat(second.events()).hasSize(1);
    AuditLog.Download links = second.events().getFirst();
    assertThat(links.name()).isEqualTo("alice");
    assertThat(links.namespaces()).containsExactly("platform-dev");
    assertThat(links.clusters()).containsExactly("k3d");
    assertThat(links.files()).isEqualTo(3);
    assertThat(links.subject()).isEqualTo("alice-subject");
    assertThat(second.next()).isNull();

    assertThat(log.totals())
        .containsEntry(AuditAction.DOWNLOAD_LINKS_ISSUED, 1L)
        .containsEntry(AuditAction.ARCHIVE_DOWNLOADED, 1L)
        .doesNotContainKey(AuditAction.EXPORT_SUBMITTED);
  }

  @Test
  void anEmptyTrailIsAnEmptyPage() {
    assertThat(log.downloads(10, null).events()).isEmpty();
    assertThat(log.totals()).isEmpty();
  }
}
