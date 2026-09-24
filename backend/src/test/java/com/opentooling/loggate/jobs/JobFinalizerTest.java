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

  private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  private static final Instant FROM = NOW.minus(Duration.ofHours(12));

  @Autowired private ExportJobRepository jobs;
  @Autowired private JdbcClient db;

  private final InMemoryObjectStore store = new InMemoryObjectStore();

  @Autowired private tools.jackson.databind.ObjectMapper json;

  private JobFinalizer finalizer() {
    return finalizer(store);
  }

  private JobFinalizer finalizer(com.opentooling.loggate.storage.ObjectStore objectStore) {
    return new JobFinalizer(
        jobs,
        objectStore,
        new com.opentooling.loggate.delivery.ManifestBuilder(jobs, Clock.fixed(NOW, ZoneOffset.UTC)),
        json,
        Duration.ofHours(48),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new com.opentooling.loggate.observability.ExportMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
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
            request,
            "{}",
            "alice-subject",
            "alice",
            List.of("ad-platform-dev"),
            List.of("platform"),
            1,
            1,
            3600),
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
  void writesAManifestBeforePublishing() {
    // An export nobody can verify is not finished, so the manifest is written
    // before the job is advertised as ready.
    UUID id = createJob();
    jobs.completeWindow(id, 0, 10, 1);

    finalizer().publishFinished();

    String key = com.opentooling.loggate.delivery.DeliveryService.manifestKey(id);
    assertThat(store.objects()).containsKey(key);
    String manifest = new String(store.objects().get(key), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(manifest).contains("\"jobId\"").contains(id.toString()).contains("\"parts\"");
    assertThat(jobs.listArtifacts(id, "MANIFEST")).hasSize(1);
  }

  @Test
  void leavesAJobUnpublishedIfItsManifestCannotBeWritten() {
    // Publishing without a manifest would advertise something unverifiable.
    UUID id = createJob();
    jobs.completeWindow(id, 0, 10, 1);
    var failing = new InMemoryObjectStore().failWith(new RuntimeException("bucket unreachable"));

    assertThat(finalizer(failing).publishFinished()).isZero();
    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.FINALIZING);
  }

  @Test
  void sweepsTheArtifactsOfAnExpiredExport() {
    // These files are production log data; retention is the whole point.
    UUID id = createJob();
    jobs.completeWindow(id, 0, 10, 1);
    finalizer().publishFinished();
    store.put(PartKeys.part(id, 0), out -> out.write("data".getBytes()));
    // Expiry is compared against the database's clock, not the test's, so this
    // has to be genuinely in the past rather than past relative to NOW.
    jobs.setExpiry(id, Instant.now().minus(Duration.ofHours(1)));

    assertThat(finalizer().sweepExpired()).isEqualTo(1);

    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.EXPIRED);
    assertThat(store.objects()).isEmpty();
  }

  @Test
  void leavesAnExpiredExportAloneIfItsArtifactsCannotBeSwept() {
    UUID id = createJob();
    jobs.completeWindow(id, 0, 10, 1);
    finalizer().publishFinished();
    jobs.setExpiry(id, Instant.now().minus(Duration.ofHours(1)));
    var failing = new InMemoryObjectStore().failWith(new RuntimeException("bucket unreachable"));

    assertThat(finalizer(failing).sweepExpired()).isZero();
    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.READY);
  }

  @Test
  void doesNotSweepAnExportThatHasNotExpiredYet() {
    UUID id = createJob();
    jobs.completeWindow(id, 0, 10, 1);
    finalizer().publishFinished();

    assertThat(finalizer().sweepExpired()).isZero();
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

    int closed = finalizer(failing).closeCancelled();

    assertThat(closed).isZero();
    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.PLANNED);
  }
}
