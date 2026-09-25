package com.opentooling.loggate.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.PostgresContainerConfig;
import com.opentooling.loggate.jobs.ExportJobRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The page's dashboard, read from the tables every replica writes to. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class ActivityRepositoryTest {

  /** Half past noon, so the newest hourly bucket is 12:00 to 13:00. */
  private static final Instant NOW = Instant.parse("2026-09-20T12:30:00Z");

  @Autowired private JdbcClient db;
  @Autowired private ExportJobRepository jobs;

  private ActivityRepository activity;

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM export_job").update();
    db.sql("DELETE FROM audit_event").update();
    activity = new ActivityRepository(db, jobs, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private UUID job(String state, String created, String started, String finished, String failure) {
    UUID id = UUID.randomUUID();
    db.sql(
            """
            INSERT INTO export_job (id, requested_by, requested_by_groups, state, failure_code,
              namespaces, selector, time_from, time_to, created_at, started_at, finished_at)
            VALUES (?, 'alice', '{}', ?, ?, '{a}', '{namespace="a"}', ?, ?, ?, ?, ?)
            """)
        .param(id)
        .param(state)
        .param(failure)
        .param(Timestamp.from(NOW.minus(Duration.ofDays(3))))
        .param(Timestamp.from(NOW.minus(Duration.ofDays(2))))
        .param(Timestamp.from(Instant.parse(created)))
        .param(started == null ? null : Timestamp.from(Instant.parse(started)))
        .param(finished == null ? null : Timestamp.from(Instant.parse(finished)))
        .update();
    return id;
  }

  private void window(UUID job, int idx, String state, String completed, long bytes, long entries) {
    db.sql(
            """
            INSERT INTO export_window (job_id, idx, window_from, window_to, state, completed_at,
              bytes_written, entries_written)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(job)
        .param(idx)
        .param(Timestamp.from(NOW.minus(Duration.ofDays(3))))
        .param(Timestamp.from(NOW.minus(Duration.ofDays(2))))
        .param(state)
        .param(completed == null ? null : Timestamp.from(Instant.parse(completed)))
        .param(bytes)
        .param(entries)
        .update();
  }

  private void audit(String action, String at) {
    db.sql("INSERT INTO audit_event (actor, action, at) VALUES ('alice', ?, ?)")
        .param(action)
        .param(Timestamp.from(Instant.parse(at)))
        .update();
  }

  @Test
  void countsWhatHappenedInEachHourOfTheLastDay() {
    UUID ready =
        job("READY", "2026-09-20T10:05:00Z", "2026-09-20T10:06:00Z", "2026-09-20T10:16:00Z", null);
    window(ready, 0, "DONE", "2026-09-20T10:10:00Z", 1000, 10);
    window(ready, 1, "DONE", "2026-09-20T11:10:00Z", 500, 5);
    // Expired was ready first, and no start time falls back to submission.
    job("EXPIRED", "2026-09-19T20:00:00Z", null, "2026-09-19T20:02:00Z", null);
    job("FAILED", "2026-09-20T11:00:00Z", "2026-09-20T11:00:00Z", "2026-09-20T11:30:00Z",
        "BYTE_LIMIT_EXCEEDED");
    job("FAILED", "2026-09-20T12:00:00Z", "2026-09-20T12:00:00Z", "2026-09-20T12:10:00Z", null);
    job("CANCELLED", "2026-09-20T12:01:00Z", null, "2026-09-20T12:02:00Z", null);
    UUID running = job("RUNNING", "2026-09-20T12:20:00Z", "2026-09-20T12:21:00Z", null, null);
    window(running, 0, "PENDING", null, 0, 0);
    // Outside the day, on either side: counted nowhere.
    job("READY", "2026-09-18T10:00:00Z", null, "2026-09-18T10:05:00Z", null);
    audit("EXPORT_REFUSED", "2026-09-20T12:15:00Z");
    audit("EXPORT_REFUSED", "2026-09-18T12:15:00Z");
    audit("NAMESPACE_ACCESS_DENIED", "2026-09-20T09:00:00Z");
    audit("EXPORT_SUBMITTED", "2026-09-20T09:00:00Z");

    ActivityReport report = activity.report(Duration.ofHours(24), Duration.ofHours(1));

    assertThat(report.from()).isEqualTo(Instant.parse("2026-09-19T13:00:00Z"));
    assertThat(report.to()).isEqualTo(Instant.parse("2026-09-20T13:00:00Z"));
    assertThat(report.bucketSeconds()).isEqualTo(3600);
    // Every hour is there, busy or not.
    assertThat(report.series()).hasSize(24);
    assertThat(report.series().getFirst().at()).isEqualTo(report.from());

    ActivityReport.Totals totals = report.totals();
    assertThat(totals.submitted()).isEqualTo(6);
    assertThat(totals.refused()).isEqualTo(1);
    assertThat(totals.denied()).isEqualTo(1);
    assertThat(totals.ready()).isEqualTo(2);
    assertThat(totals.failed()).isEqualTo(2);
    assertThat(totals.cancelled()).isEqualTo(1);
    assertThat(totals.bytesExported()).isEqualTo(1500);
    assertThat(totals.entriesExported()).isEqualTo(15);
    assertThat(report.failures())
        .containsEntry("BYTE_LIMIT_EXCEEDED", 1L)
        .containsEntry("UNKNOWN", 1L)
        .hasSize(2);

    // Bytes land in the hour they were written, not the hour the export ended.
    ActivityReport.Point tenOClock = report.series().get(21);
    assertThat(tenOClock.at()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
    assertThat(tenOClock.bytesExported()).isEqualTo(1000);
    assertThat(tenOClock.ready()).isEqualTo(1);
    assertThat(report.series().get(22).bytesExported()).isEqualTo(500);
    ActivityReport.Point noon = report.series().get(23);
    assertThat(noon.submitted()).isEqualTo(3);
    assertThat(noon.refused()).isEqualTo(1);
    assertThat(noon.failed()).isEqualTo(1);
    assertThat(noon.cancelled()).isEqualTo(1);

    // Ten minutes and two minutes, start to ready.
    assertThat(report.durationSeconds().max()).isEqualTo(600.0);
    assertThat(report.durationSeconds().p50()).isEqualTo(360.0);

    assertThat(report.now().activeExports()).isEqualTo(1);
    assertThat(report.now().windowsPending()).isEqualTo(1);
  }

  @Test
  void anEmptyPeriodIsAllZeroesAndNoDurations() {
    ActivityReport report = activity.report(Duration.ofDays(7), Duration.ofHours(6));

    assertThat(report.series()).hasSize(28);
    assertThat(report.series()).allMatch(point -> point.submitted() == 0 && point.bytesExported() == 0);
    assertThat(report.totals().bytesExported()).isZero();
    assertThat(report.failures()).isEmpty();
    assertThat(report.durationSeconds().p50()).isNull();
    assertThat(report.durationSeconds().max()).isNull();
  }
}
