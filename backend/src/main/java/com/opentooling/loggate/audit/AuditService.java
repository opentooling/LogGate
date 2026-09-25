package com.opentooling.loggate.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes the audit trail.
 *
 * <p>This is a table rather than application logs on purpose: who exported
 * production log data has to outlive the retention of the logs being exported,
 * and must not depend on the same pipeline it is auditing.
 */
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final JdbcClient db;
  private final ObjectMapper json;

  public AuditService(JdbcClient db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  /** Records one auditable action. */
  public void record(String actor, AuditAction action, Map<String, Object> detail, String sourceIp) {
    record(actor, action, null, detail, sourceIp);
  }

  /** Records one auditable action about one export, indexed by it. */
  public void record(
      String actor,
      AuditAction action,
      java.util.UUID jobId,
      Map<String, Object> detail,
      String sourceIp) {
    db.sql(
            """
            INSERT INTO audit_event (actor, action, job_id, detail, source_ip)
            VALUES (?, ?, ?, ?::jsonb, ?)
            """)
        .param(actor)
        .param(action.name())
        .param(jobId)
        .param(toJson(detail))
        .param(sourceIp)
        .update();
  }

  private String toJson(Map<String, Object> detail) {
    try {
      return json.writeValueAsString(detail);
    } catch (JacksonException e) {
      // The audit record matters more than its detail payload, so keep the row
      // and record that the detail could not be serialised.
      log.warn("Could not serialise audit detail", e);
      return "{\"serialisation_error\":true}";
    }
  }
}
