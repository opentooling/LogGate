package com.opentooling.loggate.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.PostgresContainerConfig;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.export.ExportWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The queue: claiming, leasing, completing and cancelling. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class ExportJobRepositoryTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(2);

  @Autowired private ExportJobRepository repository;
  @Autowired private JdbcClient db;

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM export_job").update();
  }

  private UUID createJob(int windowCount) {
    return createJob(windowCount, "alice-subject", Long.MAX_VALUE);
  }

  private UUID createJob(int windowCount, String subject, long byteLimit) {
    var request =
        new ExportRequest(
            List.of("platform-dev"), null, null, null, FROM, FROM.plus(Duration.ofHours(windowCount)));
    List<ExportWindow> windows =
        java.util.stream.IntStream.range(0, windowCount)
            .mapToObj(
                i ->
                    new ExportWindow(
                        i, FROM.plus(Duration.ofHours(i)), FROM.plus(Duration.ofHours(i + 1))))
            .toList();
    return repository.create(
        new NewJob(
            request,
            "{namespace=\"platform-dev\"}",
            subject,
            "alice",
            List.of("ad-platform-dev"),
            List.of("platform"),
            1024,
            byteLimit,
            3600),
        windows);
  }

  @Test
  void recordsAJobAndItsWindowsTogether() {
    UUID id = createJob(3);

    ExportJob job = repository.find(id).orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.PLANNED);
    assertThat(job.windowsTotal()).isEqualTo(3);
    assertThat(job.namespaces()).containsExactly("platform-dev");
    assertThat(repository.windowStates(id)).containsEntry(WindowState.PENDING, 3);
  }

  @Test
  void handsOutEachWindowToOnlyOneWorker() {
    UUID id = createJob(2);

    var first = repository.claimNext("worker-1", LEASE);
    var second = repository.claimNext("worker-2", LEASE);
    var third = repository.claimNext("worker-3", LEASE);

    assertThat(first).isPresent();
    assertThat(second).isPresent();
    assertThat(first.get().index()).isNotEqualTo(second.get().index());
    // Only two windows exist, so the third worker finds nothing rather than
    // waiting behind the others.
    assertThat(third).isEmpty();
  }

  @Test
  void carriesEverythingAWorkerNeedsToRunTheWindow() {
    UUID id = createJob(1, "alice-subject", 4096);

    ClaimedWindow claimed = repository.claimNext("worker-1", LEASE).orElseThrow();

    assertThat(claimed.jobId()).isEqualTo(id);
    assertThat(claimed.selector()).isEqualTo("{namespace=\"platform-dev\"}");
    assertThat(claimed.byteLimit()).isEqualTo(4096);
    assertThat(claimed.attempts()).isEqualTo(1);
    assertThat(claimed.from()).isEqualTo(FROM);
  }

  @Test
  void returnsAWindowToTheQueueOnceItsLeaseLapses() {
    // This is how a worker that died gets its work redone: nothing detects the
    // death, the lease simply stops being renewed.
    createJob(1);
    repository.claimNext("worker-that-dies", Duration.ofSeconds(-1));

    Optional<ClaimedWindow> reclaimed = repository.claimNext("worker-2", LEASE);

    assertThat(reclaimed).isPresent();
    assertThat(reclaimed.get().attempts()).isEqualTo(2);
  }

  @Test
  void doesNotHandOutAWindowWhoseLeaseIsStillLive() {
    createJob(1);
    repository.claimNext("worker-1", LEASE);

    assertThat(repository.claimNext("worker-2", LEASE)).isEmpty();
  }

  @Test
  void extendsALeaseWhileTheWindowIsProgressing() {
    createJob(1);
    repository.claimNext("worker-1", Duration.ofSeconds(1));

    repository.heartbeat(firstJobId(), 0, "worker-1", Duration.ofMinutes(10));

    assertThat(repository.claimNext("worker-2", LEASE)).isEmpty();
  }

  @Test
  void ignoresAHeartbeatFromAWorkerThatNoLongerHoldsTheLease() {
    createJob(1);
    repository.claimNext("worker-1", Duration.ofSeconds(-1));

    repository.heartbeat(firstJobId(), 0, "an-impostor", Duration.ofMinutes(10));

    assertThat(repository.claimNext("worker-2", LEASE)).isPresent();
  }

  @Test
  void rollsWindowTotalsIntoTheJobAsEachFinishes() {
    UUID id = createJob(2);
    repository.claimNext("worker-1", LEASE);

    repository.completeWindow(id, 0, 500, 5);

    ExportJob job = repository.find(id).orElseThrow();
    assertThat(job.windowsDone()).isEqualTo(1);
    assertThat(job.bytesWritten()).isEqualTo(500);
    assertThat(job.entriesWritten()).isEqualTo(5);
    assertThat(job.state()).isEqualTo(JobState.RUNNING);
    assertThat(job.progress()).isEqualTo(0.5);
  }

  @Test
  void movesToFinalizingOnceEveryWindowIsDone() {
    UUID id = createJob(2);
    repository.completeWindow(id, 0, 100, 1);
    repository.completeWindow(id, 1, 100, 1);

    assertThat(repository.find(id).orElseThrow().state()).isEqualTo(JobState.FINALIZING);
  }

  @Test
  void countsAWindowOnlyOnceEvenIfTwoWorkersFinishIt() {
    // Two workers can run the same window after a lease lapses. The part they
    // write is identical, so the second completion must not be counted twice.
    UUID id = createJob(2);
    repository.completeWindow(id, 0, 100, 1);
    repository.completeWindow(id, 0, 100, 1);

    ExportJob job = repository.find(id).orElseThrow();
    assertThat(job.windowsDone()).isEqualTo(1);
    assertThat(job.bytesWritten()).isEqualTo(100);
  }

  @Test
  void returnsAFailedWindowToTheQueueUntilItsAttemptsRunOut() {
    createJob(1);
    UUID id = firstJobId();
    repository.claimNext("worker-1", LEASE);

    boolean exhaustedFirst = repository.failWindow(id, 0, "loki said no", 2);
    repository.claimNext("worker-1", LEASE);
    boolean exhaustedSecond = repository.failWindow(id, 0, "loki said no again", 2);

    assertThat(exhaustedFirst).isFalse();
    assertThat(exhaustedSecond).isTrue();
    assertThat(repository.windowStates(id)).containsEntry(WindowState.FAILED, 1);
  }

  @Test
  void stopsHandingOutWindowsOfACancelledJob() {
    UUID id = createJob(2);

    assertThat(repository.requestCancel(id, "alice-subject")).isTrue();

    assertThat(repository.isCancelRequested(id)).isTrue();
    assertThat(repository.claimNext("worker-1", LEASE)).isEmpty();
  }

  @Test
  void refusesToCancelSomeoneElsesJob() {
    UUID id = createJob(1, "alice-subject", Long.MAX_VALUE);

    assertThat(repository.requestCancel(id, "mallory-subject")).isFalse();
    assertThat(repository.isCancelRequested(id)).isFalse();
  }

  @Test
  void refusesToCancelAJobThatAlreadyFinished() {
    UUID id = createJob(1);
    repository.finish(id, JobState.READY, null, null);

    assertThat(repository.requestCancel(id, "alice-subject")).isFalse();
  }

  @Test
  void doesNotReopenAJobThatHasAlreadyStopped() {
    UUID id = createJob(1);
    repository.finish(id, JobState.FAILED, FailureCode.UPSTREAM_FAILED, "gave up");
    repository.finish(id, JobState.READY, null, null);

    ExportJob job = repository.find(id).orElseThrow();
    assertThat(job.state()).isEqualTo(JobState.FAILED);
    assertThat(job.failureCode()).isEqualTo("UPSTREAM_FAILED");
    assertThat(job.finishedAt()).isNotNull();
  }

  @Test
  void countsJobsInFlightForQuotas() {
    createJob(1, "alice-subject", Long.MAX_VALUE);
    createJob(1, "alice-subject", Long.MAX_VALUE);
    UUID bobs = createJob(1, "bob-subject", Long.MAX_VALUE);
    repository.finish(bobs, JobState.READY, null, null);

    assertThat(repository.activeJobsFor("alice-subject")).isEqualTo(2);
    assertThat(repository.activeJobsFor("bob-subject")).isZero();
    assertThat(repository.activeJobs()).isEqualTo(2);
  }

  @Test
  void countsATeamsRecentExportsForTheDailyBudget() {
    UUID id = createJob(1);
    repository.completeWindow(id, 0, 5_000, 10);

    long used = repository.bytesExportedByTeamSince("platform", Instant.now().minusSeconds(3600));

    assertThat(used).isGreaterThanOrEqualTo(5_000);
    assertThat(repository.bytesExportedByTeamSince("payments", Instant.now().minusSeconds(3600)))
        .isZero();
  }

  @Test
  void countsAdmittedButUnfinishedExportsAtTheirEstimate() {
    // Otherwise someone could start ten large exports at once and stay under
    // budget purely because none of them had finished yet.
    createJob(4);

    assertThat(repository.bytesExportedByTeamSince("platform", Instant.now().minusSeconds(3600)))
        .isEqualTo(1024);
  }

  @Test
  void leavesFailedExportsOutOfTheBudget() {
    // A team should not lose budget to an export that produced nothing usable.
    UUID id = createJob(1);
    repository.finish(id, JobState.FAILED, FailureCode.UPSTREAM_FAILED, "gave up");

    assertThat(repository.bytesExportedByTeamSince("platform", Instant.now().minusSeconds(3600)))
        .isZero();
  }

  @Test
  void ignoresExportsOlderThanTheBudgetWindow() {
    UUID id = createJob(1);
    repository.completeWindow(id, 0, 5_000, 10);

    assertThat(repository.bytesExportedByTeamSince("platform", Instant.now().plusSeconds(60)))
        .isZero();
  }

  @Test
  void listsOnlyTheCallersJobsMostRecentFirst() {
    createJob(1, "alice-subject", Long.MAX_VALUE);
    UUID second = createJob(1, "alice-subject", Long.MAX_VALUE);
    createJob(1, "bob-subject", Long.MAX_VALUE);

    List<ExportJob> alices = repository.listFor("alice-subject", 10);

    assertThat(alices).hasSize(2);
    assertThat(alices.getFirst().id()).isEqualTo(second);
  }

  @Test
  void reportsJobsReadyToFinalizeAndCancelledJobsToClose() {
    UUID finishing = createJob(1);
    repository.completeWindow(finishing, 0, 1, 1);
    UUID cancelled = createJob(1);
    repository.requestCancel(cancelled, "alice-subject");

    assertThat(repository.jobsAwaitingFinalization()).containsExactly(finishing);
    assertThat(repository.cancelledJobsToClose()).containsExactly(cancelled);
  }

  @Test
  void willNotCloseACancelledJobWhileAWorkerStillHoldsAWindow() {
    UUID id = createJob(1);
    repository.claimNext("worker-1", LEASE);
    repository.requestCancel(id, "alice-subject");

    // The worker notices the cancellation between pages; closing underneath it
    // would delete artifacts it is still writing.
    assertThat(repository.cancelledJobsToClose()).isEmpty();
  }

  private UUID firstJobId() {
    return db.sql("SELECT id FROM export_job LIMIT 1").query(UUID.class).single();
  }
}
