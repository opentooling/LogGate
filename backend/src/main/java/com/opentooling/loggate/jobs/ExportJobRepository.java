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
                    namespaces, clusters, teams, pod_pattern, container_pattern, line_filter,
                    selector, time_from, time_to, estimated_bytes, byte_limit, window_seconds,
                    windows_total)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """)
              .param(id)
              .param(job.requestedBy())
              .param(job.requestedByName())
              .param(job.groups().toArray(String[]::new))
              .param(JobState.PLANNED.name())
              .param(job.request().namespaces().toArray(String[]::new))
              .param(job.request().clusters().toArray(String[]::new))
              .param(job.teams().toArray(String[]::new))
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

  /**
   * Bytes a team has exported since {@code since}, for the daily budget.
   *
   * <p>Counts what was actually written, plus what admitted-but-unfinished jobs
   * are expected to write. Counting only finished exports would let someone
   * start ten large jobs at once and stay under budget by virtue of none of
   * them having finished.
   */
  public long bytesExportedByTeamSince(String team, Instant since) {
    return db.sql(
            """
            SELECT COALESCE(SUM(GREATEST(bytes_written, CASE WHEN state = ANY(?)
                                                             THEN estimated_bytes ELSE 0 END)), 0)
              FROM export_job
             WHERE teams @> ARRAY[?]::text[]
               AND created_at >= ?
               AND state <> 'FAILED'
            """)
        .param(JobState.activeNames().toArray(String[]::new))
        .param(team)
        .param(java.sql.Timestamp.from(since))
        .query(Long.class)
        .single();
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

  /** Windows waiting to be claimed, for the queue-depth gauge. */
  public int pendingWindows() {
    return db.sql(
            """
            SELECT count(*)::int FROM export_window w
              JOIN export_job j ON j.id = w.job_id
             WHERE w.state IN ('PENDING', 'CLAIMED')
               AND (w.lease_expires_at IS NULL OR w.lease_expires_at < now())
               AND j.state IN ('PLANNED', 'RUNNING')
               AND NOT j.cancel_requested
            """)
        .query(Integer.class)
        .single();
  }

  /** Windows held under a live lease, for the in-flight gauge. */
  public int runningWindows() {
    return db.sql(
            """
            SELECT count(*)::int FROM export_window
             WHERE state = 'CLAIMED' AND lease_expires_at > now()
            """)
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
             bytes_written, entries_written, cancel_requested, created_at, finished_at,
             expires_at, clusters
        FROM export_job
      """;

  private ExportJob toJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    Timestamp finished = rs.getTimestamp("finished_at");
    Timestamp expires = rs.getTimestamp("expires_at");
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
        finished == null ? null : finished.toInstant(),
        expires == null ? null : expires.toInstant(),
        List.of((String[]) rs.getArray("clusters").getArray()));
  }

  /** Records an artifact belonging to a job. Replaces any earlier row for the same key. */
  public void recordArtifact(
      UUID jobId, String kind, String key, long sizeBytes, String sha256, Long crc32) {
    db.sql(
            """
            INSERT INTO export_artifact (id, job_id, kind, object_key, size_bytes, sha256, crc32)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (job_id, object_key)
            DO UPDATE SET size_bytes = EXCLUDED.size_bytes,
                          sha256 = EXCLUDED.sha256,
                          crc32 = EXCLUDED.crc32,
                          created_at = now()
            """)
        .param(UUID.randomUUID())
        .param(jobId)
        .param(kind)
        .param(key)
        .param(sizeBytes)
        .param(sha256)
        .param(crc32)
        .update();
  }

  /** A job's artifacts, in key order so parts read chronologically. */
  public List<Artifact> listArtifacts(UUID jobId, String kind) {
    return db.sql(
            """
            SELECT object_key, size_bytes, sha256, crc32 FROM export_artifact
             WHERE job_id = ? AND kind = ? ORDER BY object_key
            """)
        .param(jobId)
        .param(kind)
        .query(
            (rs, rowNum) ->
                new Artifact(
                    rs.getString("object_key"),
                    rs.getLong("size_bytes"),
                    rs.getString("sha256"),
                    rs.getObject("crc32", Long.class)))
        .list();
  }

  /** The windows of a job, for the manifest.
   *
   * @param index window position
   * @param from window start, inclusive
   * @param to window end, exclusive
   * @param entries entries extracted
   * @param uncompressedBytes uncompressed bytes extracted
   */
  public record WindowSummary(
      int index, Instant from, Instant to, long entries, long uncompressedBytes) {}

  /** @param key object key
   *  @param sizeBytes stored size
   *  @param sha256 checksum of the stored bytes
   *  @param crc32 CRC32 of the stored bytes, for streaming a ZIP without reading them twice */
  public record Artifact(String key, long sizeBytes, String sha256, Long crc32) {}

  /** Every window of a job, in order. */
  public List<WindowSummary> windowSummaries(UUID jobId) {
    return db.sql(
            """
            SELECT idx, window_from, window_to, entries_written, bytes_written
              FROM export_window WHERE job_id = ? ORDER BY idx
            """)
        .param(jobId)
        .query(
            (rs, rowNum) ->
                new WindowSummary(
                    rs.getInt("idx"),
                    rs.getTimestamp("window_from").toInstant(),
                    rs.getTimestamp("window_to").toInstant(),
                    rs.getLong("entries_written"),
                    rs.getLong("bytes_written")))
        .list();
  }

  /** Jobs whose artifacts have outlived their retention. */
  public List<UUID> expiredJobs() {
    return db.sql(
            """
            SELECT id FROM export_job
             WHERE state = 'READY' AND expires_at IS NOT NULL AND expires_at < now()
             ORDER BY expires_at LIMIT 20
            """)
        .query(UUID.class)
        .list();
  }

  /** Marks a swept job expired. */
  public void markExpired(UUID jobId) {
    db.sql(
            "UPDATE export_job SET state = 'EXPIRED', updated_at = now() WHERE id = ? AND state = 'READY'")
        .param(jobId)
        .update();
  }

  /** Used by tests and the sweeper to bound how long artifacts live. */
  public void setExpiry(UUID jobId, Instant expiresAt) {
    db.sql("UPDATE export_job SET expires_at = ? WHERE id = ?")
        .param(expiresAt == null ? null : Timestamp.from(expiresAt))
        .param(jobId)
        .update();
  }
}
