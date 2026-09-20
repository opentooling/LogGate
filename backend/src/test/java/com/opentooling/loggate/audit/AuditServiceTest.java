package com.opentooling.loggate.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.opentooling.loggate.PostgresContainerConfig;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresContainerConfig.class)
class AuditServiceTest {

  @Autowired private JdbcClient db;
  @Autowired private ObjectMapper json;

  @BeforeEach
  void clear() {
    db.sql("DELETE FROM audit_event").update();
  }

  @Test
  void writesAnAuditRowWithItsDetail() {
    new AuditService(db, json)
        .record(
            "alice-subject",
            AuditAction.NAMESPACE_ACCESS_DENIED,
            Map.of("denied", "payments-dev"),
            "10.0.0.7");

    var row =
        db.sql("SELECT actor, action, detail::text AS detail, source_ip FROM audit_event")
            .query()
            .singleRow();

    assertThat(row.get("actor")).isEqualTo("alice-subject");
    assertThat(row.get("action")).isEqualTo("NAMESPACE_ACCESS_DENIED");
    assertThat(row.get("detail").toString()).contains("payments-dev");
    assertThat(row.get("source_ip")).isEqualTo("10.0.0.7");
  }

  @Test
  void stillRecordsTheEventWhenTheDetailCannotBeSerialised() {
    // Losing the detail payload is acceptable; losing the fact that access was
    // denied is not.
    ObjectMapper failing = mock(ObjectMapper.class);
    when(failing.writeValueAsString(any()))
        .thenThrow(new tools.jackson.core.JacksonException("detail is not serialisable") {});

    new AuditService(db, failing)
        .record("bob", AuditAction.NAMESPACE_ACCESS_DENIED, Map.of("k", "v"), "10.0.0.8");

    var row = db.sql("SELECT actor, detail::text AS detail FROM audit_event").query().singleRow();
    assertThat(row.get("actor")).isEqualTo("bob");
    assertThat(row.get("detail").toString()).contains("serialisation_error");
  }
}
