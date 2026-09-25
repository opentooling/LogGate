package com.opentooling.loggate.activity;

import com.opentooling.loggate.jobs.ExportJobRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Builds {@link ActivityReport}s from the job tables and the audit trail.
 *
 * <p>Read from the database rather than from the process's own metrics: the
 * counters in each replica only know what that replica did since it started,
 * while the database knows what every replica did, and still knows it after a
 * restart. The Prometheus metrics answer the same questions for Grafana.
 */
public class ActivityRepository {

  private final JdbcClient db;
  private final ExportJobRepository jobs;
  private final Clock clock;

  public ActivityRepository(JdbcClient db, ExportJobRepository jobs, Clock clock) {
    this.db = db;
    this.jobs = jobs;
    this.clock = clock;
  }

  /** The last {@code period}, in buckets of {@code bucket}. */
  public ActivityReport report(Duration period, Duration bucket) {
    long width = bucket.toSeconds();
    // Buckets are aligned to the bucket width, so the same hour is the same
    // bucket on every request, and the newest bucket is the one now is in.
    long end = (clock.instant().getEpochSecond() / width + 1) * width;
    long start = end - (period.toSeconds() / width) * width;
    Instant from = Instant.ofEpochSecond(start);
    Instant to = Instant.ofEpochSecond(end);

    Map<Long, long[]> buckets = new LinkedHashMap<>();
    for (long at = start; at < end; at += width) {
      buckets.put(at, new long[6]);
    }

    // Accepted exports, by when they were submitted.
    count(
        """
        SELECT floor(extract(epoch FROM created_at) / :width)::bigint * :width AS at, count(*) AS n
        FROM export_job WHERE created_at >= :from AND created_at < :to GROUP BY 1
        """,
        from, to, width, buckets, 0);
    // Quota refusals, which leave no job behind, from the audit trail.
    count(
        """
        SELECT floor(extract(epoch FROM at) / :width)::bigint * :width AS at, count(*) AS n
        FROM audit_event
        WHERE action = 'EXPORT_REFUSED' AND at >= :from AND at < :to GROUP BY 1
        """,
        from, to, width, buckets, 1);
    // Outcomes, by when they were reached. EXPIRED was READY first.
    finished("('READY', 'EXPIRED')", from, to, width, buckets, 2);
    finished("('FAILED')", from, to, width, buckets, 3);
    finished("('CANCELLED')", from, to, width, buckets, 4);
    // Volume by when it was written, which for a long export spreads it over
    // the hours it ran rather than piling it on the hour it finished.
    db.sql(
            """
            SELECT floor(extract(epoch FROM completed_at) / :width)::bigint * :width AS at,
                   coalesce(sum(bytes_written), 0) AS n
            FROM export_window
            WHERE completed_at >= :from AND completed_at < :to GROUP BY 1
            """)
        .param("width", width)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query((rs, row) -> Map.entry(rs.getLong("at"), rs.getLong("n")))
        .list()
        .forEach(e -> add(buckets, e.getKey(), 5, e.getValue()));

    List<ActivityReport.Point> series = new ArrayList<>();
    long[] sums = new long[6];
    buckets.forEach(
        (at, v) -> {
          series.add(
              new ActivityReport.Point(Instant.ofEpochSecond(at), v[0], v[1], v[2], v[3], v[4], v[5]));
          for (int i = 0; i < v.length; i++) {
            sums[i] += v[i];
          }
        });

    long denied =
        db.sql(
                """
                SELECT count(*) FROM audit_event
                WHERE action = 'NAMESPACE_ACCESS_DENIED' AND at >= :from AND at < :to
                """)
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query(Long.class)
            .single();
    long entries =
        db.sql(
                """
                SELECT coalesce(sum(entries_written), 0) FROM export_window
                WHERE completed_at >= :from AND completed_at < :to
                """)
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query(Long.class)
            .single();

    Map<String, Long> failures = new HashMap<>();
    db.sql(
            """
            SELECT coalesce(failure_code, 'UNKNOWN') AS code, count(*) AS n FROM export_job
            WHERE state = 'FAILED' AND finished_at >= :from AND finished_at < :to GROUP BY 1
            """)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query((rs, row) -> Map.entry(rs.getString("code"), rs.getLong("n")))
        .list()
        .forEach(e -> failures.put(e.getKey(), e.getValue()));

    ActivityReport.Durations durations =
        db.sql(
                """
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY s) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY s) AS p95,
                       max(s) AS max
                FROM (
                  SELECT extract(epoch FROM finished_at - coalesce(started_at, created_at))::double precision AS s
                  FROM export_job
                  WHERE state IN ('READY', 'EXPIRED')
                    AND finished_at >= :from AND finished_at < :to
                ) finished
                """)
            .param("from", Timestamp.from(from))
            .param("to", Timestamp.from(to))
            .query(
                (rs, row) ->
                    new ActivityReport.Durations(
                        rs.getObject("p50", Double.class),
                        rs.getObject("p95", Double.class),
                        rs.getObject("max", Double.class)))
            .single();

    return new ActivityReport(
        from,
        to,
        width,
        new ActivityReport.Now(jobs.activeJobs(), jobs.pendingWindows(), jobs.runningWindows()),
        new ActivityReport.Totals(
            sums[0], sums[1], denied, sums[2], sums[3], sums[4], sums[5], entries),
        Map.copyOf(failures),
        durations,
        List.copyOf(series));
  }

  private void finished(
      String states, Instant from, Instant to, long width, Map<Long, long[]> buckets, int slot) {
    count(
        """
        SELECT floor(extract(epoch FROM finished_at) / :width)::bigint * :width AS at, count(*) AS n
        FROM export_job
        WHERE state IN %s AND finished_at >= :from AND finished_at < :to GROUP BY 1
        """
            .formatted(states),
        from, to, width, buckets, slot);
  }

  private void count(
      String sql, Instant from, Instant to, long width, Map<Long, long[]> buckets, int slot) {
    db.sql(sql)
        .param("width", width)
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query((rs, row) -> Map.entry(rs.getLong("at"), rs.getLong("n")))
        .list()
        .forEach(e -> add(buckets, e.getKey(), slot, e.getValue()));
  }

  private static void add(Map<Long, long[]> buckets, long at, int slot, long value) {
    long[] bucket = buckets.get(at);
    if (bucket != null) {
      bucket[slot] += value;
    }
  }
}
