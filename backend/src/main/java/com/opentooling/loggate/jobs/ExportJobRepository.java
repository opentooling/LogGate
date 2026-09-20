package com.opentooling.loggate.jobs;

import com.opentooling.loggate.export.ExportWindow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Jobs, their windows, and the queue - all in PostgreSQL, because the job state
 * and the work queue are the same data. Splitting them across a broker would
 * buy a distributed-consistency problem in exchange for nothing.
 *
 * <p>Hand-written SQL throughout: the claim query is the heart of the system
 * and deserves to be read as SQL rather than inferred from a mapping.
 */
public class ExportJobRepository {

  private final JdbcClient db;
  private final TransactionTemplate transactions;

  public ExportJobRepository(JdbcClient db, TransactionTemplate transactions) {
    this.db = db;
    this.transactions = transactions;
  }

  /** Records an admitted job and its windows atomically. */
  public UUID create(NewJob job, List<ExportWindow> windows) {
    UUID id = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          db.sql(
                  """
                  INSERT INTO export_job (
                    id, requested_by, requested_by_name, requested_by_groups, state,
                    namespaces, pod_pattern, container_pattern, line_filter, selector,
                    time_from, time_to, estimated_bytes, byte_limit, window_seconds,
                    windows_total)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """)
              .param(id)
              .param(job.requestedBy())
              .param(job.requestedByName())
              .param(job.groups().toArray(String[]::new))
              .param(JobState.PLANNED.name())
              .param(job.request().namespaces().toArray(String[]::new))
              .param(job.request().podPattern())
              .param(job.request().containerPattern())
              .param(job.request().lineFilter())
              .param(job.selector())
              .param(Timestamp.from(job.request().from()))
              .param(Timestamp.from(job.request().to()))
              .param(job.estimatedBytes())
              .param(job.byteLimit())
              .param((int) job.windowSeconds())
              .param(windows.size())
              .update();

          for (ExportWindow window : windows) {
            db.sql(
                    """
                    INSERT INTO export_window (job_id, idx, window_from, window_to, state)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """)
                .param(id)
                .param(window.index())
                .param(Timestamp.from(window.from()))
                .param(Timestamp.from(window.to()))
                .update();
          }
        });
    return id;
  }

  /**
   * Takes a lease on the next runnable window.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} is what lets many workers claim
   * concurrently without coordinating: each skips rows another worker already
   * holds instead of waiting behind them. A window whose lease has lapsed is
   * claimable again, which is how a dead worker's work returns to the queue.
   */
  public Optional<ClaimedWindow> claimNext(String owner, Duration lease) {
    return db.sql(
            """
            UPDATE export_window w
               SET state = 'CLAIMED',
                   lease_owner = :owner,
                   lease_expires_at = now() + (:leaseSeconds * interval '1 second'),
                   attempts = w.attempts + 1
             WHERE (w.job_id, w.idx) IN (
                     SELECT c.job_id, c.idx
                       FROM export_window c
                       JOIN export_job j ON j.id = c.job_id
                      WHERE c.state IN ('PENDING', 'CLAIMED')
                        AND (c.lease_expires_at IS NULL OR c.lease_expires_at < now())
                        AND j.state IN ('PLANNED', 'RUNNING')
                        AND NOT j.cancel_requested
                      ORDER BY c.lease_expires_at NULLS FIRST, c.job_id, c.idx
                      FOR UPDATE OF c SKIP LOCKED
                      LIMIT 1)
            RETURNING w.job_id, w.idx, w.window_from, w.window_to, w.attempts,
                      (SELECT j.selector FROM export_job j WHERE j.id = w.job_id) AS selector,
                      (SELECT j.byte_limit FROM export_job j WHERE j.id = w.job_id) AS byte_limit,
                      (SELECT j.bytes_written FROM export_job j WHERE j.id = w.job_id) AS bytes_written
            """)
        .param("owner", owner)
        .param("leaseSeconds", lease.toSeconds())
        .query(
            (rs, rowNum) ->
                new ClaimedWindow(
                    rs.getObject("job_id", UUID.class),
                    rs.getInt("idx"),
                    rs.getTimestamp("window_from").toInstant(),
                    rs.getTimestamp("window_to").toInstant(),
                    rs.getString("selector"),
                    rs.getLong("byte_limit"),
                    rs.getLong("bytes_written"),
                    rs.getInt("attempts")))
        .optional();
  }

  /** Extends a lease while a window is genuinely progressing. */
  public void heartbeat(UUID jobId, int index, String owner, Duration lease) {
    db.sql(
            """
            UPDATE export_window
               SET lease_expires_at = now() + (? * interval '1 second')
             WHERE job_id = ? AND idx = ? AND lease_owner = ?
            """)
        .param(lease.toSeconds())
        .param(jobId)
        .param(index)
        .param(owner)
        .update();
  }

  /** Marks a window finished and rolls its totals into the job. */
  public void completeWindow(UUID jobId, int index, long bytes, long entries) {
    transactions.executeWithoutResult(
        status -> {
          int updated =
              db.sql(
                      """
                      UPDATE export_window
                         SET state = 'DONE', completed_at = now(), last_error = NULL,
                             bytes_written = ?, entries_written = ?, lease_expires_at = NULL
                       WHERE job_id = ? AND idx = ? AND state <> 'DONE'
                      """)
                  .param(bytes)
                  .param(entries)
                  .param(jobId)
                  .param(index)
                  .update();
          if (updated == 0) {
            // Already completed by another worker whose lease had lapsed. The
            // part is identical either way, so there is nothing to undo.
            return;
          }
          db.sql(
                  """
                  UPDATE export_job
                     SET windows_done = windows_done + 1,
                         bytes_written = bytes_written + ?,
                         entries_written = entries_written + ?,
                         state = CASE WHEN windows_done + 1 >= windows_total
                                      THEN 'FINALIZING' ELSE 'RUNNING' END,
                         started_at = COALESCE(started_at, now()),
                         updated_at = now()
                   WHERE id = ?
                  """)
              .param(bytes)
              .param(entries)
              .param(jobId)
              .update();
        });
  }

  /** Records a failed attempt, giving up on the window once attempts run out. */
  public boolean failWindow(UUID jobId, int index, String error, int maxAttempts) {
    return db.sql(
            """
            UPDATE export_window
               SET state = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                   last_error = ?, lease_expires_at = NULL
             WHERE job_id = ? AND idx = ?
            RETURNING state = 'FAILED' AS exhausted
            """)
        .param(maxAttempts)
        .param(error)
        .param(jobId)
        .param(index)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  /** Whether a cancellation has been asked for. Checked between pages. */
  public boolean isCancelRequested(UUID jobId) {
    return db.sql("SELECT cancel_requested FROM export_job WHERE id = ?")
        .param(jobId)
        .query(Boolean.class)
        .optional()
        .orElse(false);
  }

  /** Asks a running job to stop. Workers notice between pages. */
  public boolean requestCancel(UUID jobId, String requestedBy) {
    return db.sql(
            """
            UPDATE export_job
               SET cancel_requested = true, updated_at = now()
             WHERE id = ? AND requested_by = ? AND state IN ('QUEUED','PLANNED','RUNNING','FINALIZING')
            """)
        .param(jobId)
        .param(requestedBy)
        .update()
        > 0;
  }

  /** Moves a job to a terminal state, if it is not already in one. */
  public void finish(UUID jobId, JobState state, FailureCode code, String detail) {
    db.sql(
            """
            UPDATE export_job
               SET state = ?, failure_code = ?, failure_detail = ?,
                   finished_at = now(), updated_at = now()
             WHERE id = ? AND state IN ('QUEUED','PLANNED','RUNNING','FINALIZING')
            """)
        .param(state.name())
        .param(code == null ? null : code.name())
        .param(detail)
        .param(jobId)
        .update();
  }

  /** Jobs that have finished every window, ready to be finalized. */
  public List<UUID> jobsAwaitingFinalization() {
    return db.sql(
            "SELECT id FROM export_job WHERE state = 'FINALIZING' ORDER BY updated_at LIMIT 20")
        .query(UUID.class)
        .list();
  }

  /** Jobs asked to stop that still hold no running windows. */
  public List<UUID> cancelledJobsToClose() {
    return db.sql(
            """
            SELECT j.id FROM export_job j
             WHERE j.cancel_requested
               AND j.state IN ('QUEUED','PLANNED','RUNNING','FINALIZING')
               AND NOT EXISTS (
                     SELECT 1 FROM export_window w
                      WHERE w.job_id = j.id AND w.state = 'CLAIMED'
                        AND w.lease_expires_at > now())
             LIMIT 20
            """)
        .query(UUID.class)
        .list();
  }

  /** How many jobs the caller has in flight, for the concurrency quota. */
  public int activeJobsFor(String requestedBy) {
    return db.sql(
            "SELECT count(*)::int FROM export_job WHERE requested_by = ? AND state = ANY(?)")
        .param(requestedBy)
        .param(JobState.activeNames().toArray(String[]::new))
        .query(Integer.class)
        .single();
  }

  /** How many jobs are in flight across everyone, for the global quota. */
  public int activeJobs() {
    return db.sql("SELECT count(*)::int FROM export_job WHERE state = ANY(?)")
        .param(JobState.activeNames().toArray(String[]::new))
        .query(Integer.class)
        .single();
  }

  /** One job, whoever owns it. */
  public Optional<ExportJob> find(UUID id) {
    return db.sql(SELECT_JOB + " WHERE id = ?").param(id).query(this::toJob).optional();
  }

  /** The caller's jobs, most recent first. */
  public List<ExportJob> listFor(String requestedBy, int limit) {
    return db.sql(SELECT_JOB + " WHERE requested_by = ? ORDER BY created_at DESC LIMIT ?")
        .param(requestedBy)
        .param(limit)
        .query(this::toJob)
        .list();
  }

  /** The job's window states, for a progress view. */
  public Map<WindowState, Integer> windowStates(UUID jobId) {
    return db
        .sql("SELECT state, count(*)::int AS n FROM export_window WHERE job_id = ? GROUP BY state")
        .param(jobId)
        .query()
        .listOfRows()
        .stream()
        .collect(
            java.util.stream.Collectors.toMap(
                row -> WindowState.valueOf((String) row.get("state")),
                row -> (Integer) row.get("n")));
  }

  private static final String SELECT_JOB =
      """
      SELECT id, requested_by, state, failure_code, failure_detail, namespaces, selector,
             time_from, time_to, estimated_bytes, byte_limit, windows_total, windows_done,
             bytes_written, entries_written, cancel_requested, created_at, finished_at
        FROM export_job
      """;

  private ExportJob toJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    return new ExportJob(
        rs.getObject("id", UUID.class),
        rs.getString("requested_by"),
        JobState.valueOf(rs.getString("state")),
        rs.getString("failure_code"),
        rs.getString("failure_detail"),
        List.of((String[]) rs.getArray("namespaces").getArray()),
        rs.getString("selector"),
        rs.getTimestamp("time_from").toInstant(),
        rs.getTimestamp("time_to").toInstant(),
        rs.getLong("estimated_bytes"),
        rs.getLong("byte_limit"),
        rs.getInt("windows_total"),
        rs.getInt("windows_done"),
        rs.getLong("bytes_written"),
        rs.getLong("entries_written"),
        rs.getBoolean("cancel_requested"),
        rs.getTimestamp("created_at").toInstant(),
        finished == null ? null : finished.toInstant());
  }

  /** Used by tests and the sweeper to bound how long artifacts live. */
  public void setExpiry(UUID jobId, Instant expiresAt) {
    db.sql("UPDATE export_job SET expires_at = ? WHERE id = ?")
        .param(expiresAt == null ? null : Timestamp.from(expiresAt))
        .param(jobId)
        .update();
  }
}
