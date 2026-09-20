package com.opentooling.loggate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Boots the application against a real Postgres and asserts that Liquibase
 * produced the schema the rest of the system assumes.
 */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class SchemaMigrationTest {

  @Autowired private JdbcClient db;

  @Test
  void createsTheCoreTables() {
    List<String> tables =
        db.sql(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' ORDER BY table_name
                """)
            .query(String.class)
            .list();

    assertThat(tables)
        .contains("export_job", "export_window", "export_artifact", "audit_event");
  }

  @Test
  void enforcesTheJobTimeRangeInvariant() {
    Integer violations =
        db.sql(
                """
                SELECT count(*)::int FROM information_schema.check_constraints
                WHERE constraint_name = 'export_job_time_range'
                """)
            .query(Integer.class)
            .single();

    assertThat(violations).isEqualTo(1);
  }

  @Test
  void windowsCascadeWhenTheirJobIsDeleted() {
    var jobId = java.util.UUID.randomUUID();
    db.sql(
            """
            INSERT INTO export_job (id, requested_by, requested_by_groups, state,
                                    namespaces, selector, time_from, time_to)
            VALUES (?, 'alice', ARRAY['ad-platform-dev'], 'QUEUED',
                    ARRAY['platform'], '{namespace="platform"}',
                    now() - interval '2 days', now())
            """)
        .param(jobId)
        .update();
    db.sql(
            """
            INSERT INTO export_window (job_id, idx, window_from, window_to)
            VALUES (?, 0, now() - interval '2 days', now() - interval '47 hours')
            """)
        .param(jobId)
        .update();

    db.sql("DELETE FROM export_job WHERE id = ?").param(jobId).update();

    Integer remaining =
        db.sql("SELECT count(*)::int FROM export_window WHERE job_id = ?")
            .param(jobId)
            .query(Integer.class)
            .single();
    assertThat(remaining).isZero();
  }
}
