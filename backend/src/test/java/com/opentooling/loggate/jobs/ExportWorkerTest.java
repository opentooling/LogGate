package com.opentooling.loggate.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.PostgresContainerConfig;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.export.ExportWindow;
import com.opentooling.loggate.export.WindowPager;
import com.opentooling.loggate.loki.FakeLokiClient;
import com.opentooling.loggate.loki.LokiException;
import com.opentooling.loggate.storage.InMemoryObjectStore;
import com.opentooling.loggate.storage.PartKeys;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** What a worker does with a window, including every way it can stop early. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class ExportWorkerTest {

  private static final long SECOND = 1_000_000_000L;
  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final long FROM_NANOS = FROM.getEpochSecond() * SECOND;

  @Autowired private ExportJobRepository jobs;
  @Autowired private JdbcClient db;
  @Autowired private ObjectMapper json;

  private final InMemoryObjectStore store = new InMemoryObjectStore();

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM export_job").update();
  }

  private UUID createJob(long byteLimit) {
    var request =
        new ExportRequest(
            List.of("platform-dev"), null, null, null, FROM, FROM.plus(Duration.ofMinutes(10)));
    return jobs.create(
        new NewJob(
            request,
            "{namespace=\"platform-dev\"}",
            "alice-subject",
            "alice",
            List.of("ad-platform-dev"),
            List.of("platform"),
            1024,
            byteLimit,
            600),
        List.of(new ExportWindow(0, FROM, FROM.plus(Duration.ofMinutes(10)))));
  }

  private ExportWorker worker(FakeLokiClient loki, InMemoryObjectStore objectStore, int maxAttempts) {
    return worker(loki, objectStore, maxAttempts, 5_000);
  }

  private ExportWorker worker(
      FakeLokiClient loki, InMemoryObjectStore objectStore, int maxAttempts, long checkEvery) {
    return new ExportWorker(
        jobs,
        new WindowPager(loki, 100),
        objectStore,
        json,
        "test-worker",
        Duration.ofMinutes(2),
        maxAttempts,
        checkEvery,
        new com.opentooling.loggate.observability.ExportMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
  }

  private static String unzip(byte[] gzipped) {
    try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void writesTheWindowAsGzippedJsonLinesAndCompletesIt() {
    UUID id = createJob(Long.MAX_VALUE);
    var loki = new FakeLokiClient().entry(FROM_NANOS, "api-0", "hello").entry(FROM_NANOS + 1, "api-1", "world");

    assertThat(worker(loki, store, 3).runOnce()).isTrue();

    String part = unzip(store.objects().get(PartKeys.part(id, 0)));
    assertThat(part.lines()).hasSize(2);
    assertThat(part).contains("\"line\":\"hello\"").contains("\"pod\":\"api-1\"");

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.entriesWritten()).isEqualTo(2);
    assertThat(job.bytesWritten()).isPositive();
    assertThat(job.state()).isEqualTo(JobState.FINALIZING);
  }

  @Test
  void reportsThereWasNothingToDoWhenTheQueueIsEmpty() {
    assertThat(worker(new FakeLokiClient(), store, 3).runOnce()).isFalse();
  }

  @Test
  void writesAnEmptyPartForAWindowWithNoLogs() {
    // A window with no data is still done. Leaving it unwritten would make the
    // manifest ambiguous about whether it ran.
    UUID id = createJob(Long.MAX_VALUE);

    worker(new FakeLokiClient(), store, 3).runOnce();

    assertThat(store.objects()).containsKey(PartKeys.part(id, 0));
    assertThat(unzip(store.objects().get(PartKeys.part(id, 0)))).isEmpty();
    assertThat(jobs.find(id).orElseThrow().windowsDone()).isEqualTo(1);
  }

  @Test
  void failsTheWholeJobWhenItExceedsItsByteLimit() {
    // Not just the window: every other window of an over-limit job is pointless.
    UUID id = createJob(10);
    var loki = new FakeLokiClient();
    for (int i = 0; i < 50; i++) {
      loki.entry(FROM_NANOS + i, "api-0", "a reasonably long log line number " + i);
    }

    worker(loki, store, 3).runOnce();

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.FAILED);
    assertThat(job.failureCode()).isEqualTo("BYTE_LIMIT_EXCEEDED");
    assertThat(job.failureDetail()).contains("over its limit");
  }

  @Test
  void holdsTheCapToTheLogLinesRatherThanTheJsonAroundThem() {
    // The cap comes from the estimate, which counts log lines as Loki does.
    // JSON lines carry each entry's labels and timestamp too, two to three
    // times the size, and holding them to the cap failed exports that were
    // well within it.
    var loki = new FakeLokiClient();
    long logBytes = 0;
    for (int i = 0; i < 20; i++) {
      String line = "request served " + i;
      loki.entry(FROM_NANOS + i, "api-0", line);
      logBytes += line.length();
    }
    UUID id = createJob(logBytes + 10);

    worker(loki, store, 3, 1).runOnce();

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.FINALIZING);
    assertThat(job.logBytes()).isEqualTo(logBytes);
    assertThat(job.bytesWritten()).isGreaterThan(2 * logBytes);
  }

  @Test
  void stopsMidWindowWhenTheJobIsCancelled() {
    UUID id = createJob(Long.MAX_VALUE);
    jobs.requestCancel(id, "alice-subject");
    var loki = new FakeLokiClient().entry(FROM_NANOS, "api-0", "hello");

    // A cancelled job's windows are not handed out at all, so there is nothing
    // to claim and the worker reports no work.
    assertThat(worker(loki, store, 3).runOnce()).isFalse();
    assertThat(jobs.find(id).orElseThrow().windowsDone()).isZero();
  }

  @Test
  void stopsPartWayThroughAWindowWhenTheJobIsCancelledWhileItRuns() {
    // The realistic case: the cancellation arrives after the window started, so
    // the worker has to notice between pages rather than at the start.
    UUID id = createJob(Long.MAX_VALUE);
    var loki = new FakeLokiClient();
    for (int i = 0; i < 20; i++) {
      loki.entry(FROM_NANOS + i, "api-0", "line " + i);
    }
    // Claim first, then cancel, so the window is already in flight.
    var worker = worker(loki, store, 3, 1);
    jobs.claimNext("someone-else", Duration.ofSeconds(-1));
    jobs.requestCancel(id, "alice-subject");

    worker.runOnce();

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.windowsDone()).isZero();
    assertThat(store.objects()).isEmpty();
  }

  @Test
  void enforcesTheByteCapPartWayThroughAWindowRatherThanAtTheEnd() {
    // Catching it only at the end would mean writing the whole over-limit
    // window first, which is exactly what the cap exists to prevent.
    UUID id = createJob(50);
    var loki = new FakeLokiClient();
    for (int i = 0; i < 40; i++) {
      loki.entry(FROM_NANOS + i, "api-0", "a log line that is not especially short, number " + i);
    }

    worker(loki, store, 3, 1).runOnce();

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.FAILED);
    assertThat(job.failureCode()).isEqualTo("BYTE_LIMIT_EXCEEDED");
  }

  @Test
  void reportsAByteLimitAsAQuotaFailureEvenThoughItStoppedMidUpload() {
    // The limit is hit while the part is being written, so the exception
    // travels out through the object store. It must still arrive as a quota
    // failure: telling the user their export is a storage problem sends them
    // to the wrong place entirely.
    UUID id = createJob(50);
    var loki = new FakeLokiClient();
    for (int i = 0; i < 40; i++) {
      loki.entry(FROM_NANOS + i, "api-0", "a log line that is not especially short, number " + i);
    }

    worker(loki, store, 3, 1).runOnce();

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.failureCode()).isEqualTo("BYTE_LIMIT_EXCEEDED");
    assertThat(job.failureCode()).isNotEqualTo("STORAGE_FAILED");
  }

  @Test
  void describesAFailureThatCarriesNoMessage() {
    UUID id = createJob(Long.MAX_VALUE);
    var failing = new InMemoryObjectStore().failWith(new IllegalStateException());

    worker(new FakeLokiClient().entry(FROM_NANOS, "api-0", "x"), failing, 1).runOnce();

    assertThat(jobs.find(id).orElseThrow().failureDetail()).contains("IllegalStateException");
  }

  @Test
  void retriesAWindowThatFailedUpstreamBeforeGivingUpOnTheJob() {
    UUID id = createJob(Long.MAX_VALUE);
    var loki = new FakeLokiClient();
    loki.failNextQuery(new LokiException("loki is down"));

    worker(loki, store, 2).runOnce();
    assertThat(jobs.find(id).orElseThrow().state()).isEqualTo(JobState.PLANNED);

    loki.failNextQuery(new LokiException("loki is still down"));
    worker(loki, store, 2).runOnce();

    ExportJob job = jobs.find(id).orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.FAILED);
    assertThat(job.failureCode()).isEqualTo("UPSTREAM_FAILED");
  }

  @Test
  void distinguishesAStorageFailureFromAnUpstreamOne() {
    // The two need different responses from an operator, so they are not
    // collapsed into one failure code.
    UUID id = createJob(Long.MAX_VALUE);
    var failing =
        new InMemoryObjectStore().failWith(new UncheckedIOException(new IOException("bucket gone")));

    worker(new FakeLokiClient().entry(FROM_NANOS, "api-0", "x"), failing, 1).runOnce();

    assertThat(jobs.find(id).orElseThrow().failureCode()).isEqualTo("STORAGE_FAILED");
  }

  @Test
  void aRetriedWindowOverwritesItsPartRatherThanAddingASecondCopy() {
    // The whole idempotency argument in one test: the key is derived from the
    // job and index, so running the window twice leaves one object.
    UUID id = createJob(Long.MAX_VALUE);
    var loki = new FakeLokiClient().entry(FROM_NANOS, "api-0", "hello");

    worker(loki, store, 3).runOnce();
    db.sql("UPDATE export_window SET state = 'PENDING', lease_expires_at = NULL WHERE job_id = ?")
        .param(id)
        .update();
    db.sql("UPDATE export_job SET state = 'PLANNED' WHERE id = ?").param(id).update();
    worker(loki, store, 3).runOnce();

    assertThat(store.objects()).hasSize(1);
    assertThat(unzip(store.objects().get(PartKeys.part(id, 0))).lines()).hasSize(1);
  }
}
