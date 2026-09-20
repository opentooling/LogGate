package com.opentooling.loggate.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.PostgresContainerConfig;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.export.ExportWindow;
import com.opentooling.loggate.storage.InMemoryObjectStore;
import com.opentooling.loggate.storage.PartKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresContainerConfig.class)
class JobFinalizerTest {

  private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");

  @Autowired private ExportJobRepository jobs;
  @Autowired private JdbcClient db;

  private final InMemoryObjectStore store = new InMemoryObjectStore();

  private JobFinalizer finalizer() {
    return new JobFinalizer(
        jobs, store, Duration.ofHours(48), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM export_job").update();
  }

  private UUID createJob() {
    var request =
        new ExportRequest(List.of("platform-dev"), null, null, null, FROM, FROM.plusSeconds(3600));
    return jobs.create(
        new NewJob(
            request, "{}", "alice-subject", "alice", List.of("ad-platform-dev"), 1, 1, 3600),
        List.of(new ExportWindow(0, FROM, FROM.plusSeconds(3600))));
  }

  @Test
  void publishesAJobWhoseWindowsHaveAllFinished() {
    UUID id = createJob();
    jobs.completeWindow(id, 0, 10, 1);

    assertThat(finalizer().publishFinished()).isEqualTo(1);

    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.READY);
    Instant expiry =
        db.sql("SELECT expires_at FROM export_job WHERE id = ?")
            .param(id)
            .query(java.sql.Timestamp.class)
            .single()
            .toInstant();
    // The artifact's life starts when it becomes downloadable, not when the
    // export was requested.
    assertThat(expiry).isEqualTo(NOW.plus(Duration.ofHours(48)));
  }

  @Test
  void leavesAnUnfinishedJobAlone() {
    createJob();

    assertThat(finalizer().publishFinished()).isZero();
  }

  @Test
  void purgesTheArtifactsOfACancelledJob() {
    // A cancelled export is not a partial export: fragments of production logs
    // must not be left in the bucket.
    UUID id = createJob();
    store.put(PartKeys.part(id, 0), out -> out.write("partial".getBytes()));
    jobs.requestCancel(id, "alice-subject");

    assertThat(finalizer().closeCancelled()).isEqualTo(1);

    assertThat(store.objects()).isEmpty();
    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.CANCELLED);
  }

  @Test
  void leavesACancelledJobOpenIfItsArtifactsCannotBePurged() {
    // Better to retry on the next pass than to mark it cancelled while its
    // artifacts are still sitting in the bucket.
    UUID id = createJob();
    jobs.requestCancel(id, "alice-subject");
    var failing = new InMemoryObjectStore().failWith(new RuntimeException("bucket unreachable"));

    int closed =
        new JobFinalizer(jobs, failing, Duration.ofHours(48), Clock.fixed(NOW, ZoneOffset.UTC))
            .closeCancelled();

    assertThat(closed).isZero();
    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.PLANNED);
  }
}
