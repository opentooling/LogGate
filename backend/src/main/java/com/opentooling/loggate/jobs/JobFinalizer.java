package com.opentooling.loggate.jobs;

import com.opentooling.loggate.storage.ObjectStore;
import com.opentooling.loggate.storage.PartKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves jobs out of their in-between states.
 *
 * <p>Separate from the workers because both transitions are about a job as a
 * whole, and a worker only ever sees one window.
 */
public class JobFinalizer {

  private static final Logger log = LoggerFactory.getLogger(JobFinalizer.class);

  private final ExportJobRepository jobs;
  private final ObjectStore store;
  private final Duration retention;
  private final Clock clock;

  public JobFinalizer(
      ExportJobRepository jobs, ObjectStore store, Duration retention, Clock clock) {
    this.jobs = jobs;
    this.store = store;
    this.retention = retention;
    this.clock = clock;
  }

  /**
   * Publishes jobs whose windows have all finished.
   *
   * <p>The expiry is set here rather than at submission, so an export's life
   * starts when it becomes downloadable rather than when it was asked for.
   */
  public int publishFinished() {
    int published = 0;
    for (UUID id : jobs.jobsAwaitingFinalization()) {
      jobs.setExpiry(id, clock.instant().plus(retention));
      jobs.finish(id, JobState.READY, null, null);
      log.info("job {} is ready, expiring in {}", id, retention);
      published++;
    }
    return published;
  }

  /**
   * Closes jobs that were asked to stop, once no worker still holds a window.
   *
   * <p>The parts are deleted: a cancelled export is not a partial export, and
   * leaving fragments of production logs in a bucket is exactly what the
   * retention rules exist to prevent.
   */
  public int closeCancelled() {
    int closed = 0;
    for (UUID id : jobs.cancelledJobsToClose()) {
      try {
        store.deletePrefix(PartKeys.jobPrefix(id));
      } catch (RuntimeException e) {
        // Recorded and retried on the next pass; the bucket lifecycle rule is
        // the backstop if this never succeeds.
        log.warn("could not purge artifacts of cancelled job {}", id, e);
        continue;
      }
      jobs.finish(id, JobState.CANCELLED, null, "cancelled by request");
      log.info("job {} cancelled and its artifacts purged", id);
      closed++;
    }
    return closed;
  }
}
