package com.opentooling.loggate.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the audit trail back, for the administrators' audit page.
 *
 * <p>Newest first, a page at a time, keyed on the event id rather than an
 * offset, so a page does not shift as new downloads are recorded above it.
 */
public class AuditLog {

  private final JdbcClient db;
  private final ObjectMapper json;

  public AuditLog(JdbcClient db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  /**
   * One download.
   *
   * @param id the event's id, which pages are keyed on
   * @param at when it happened
   * @param subject who, by their stable OIDC subject
   * @param name who, by the name they signed in with at the time
   * @param action how the data was handed over
   * @param jobId which export
   * @param namespaces the export's namespaces; empty meaning every one
   * @param clusters the export's clusters, when Loki holds several
   * @param files how many files the links covered, when links were issued
   * @param bytes the export's size, uncompressed
   * @param sourceIp where the request came from
   */
  public record Download(
      long id,
      Instant at,
      String subject,
      String name,
      AuditAction action,
      UUID jobId,
      List<String> namespaces,
      List<String> clusters,
      Integer files,
      Long bytes,
      String sourceIp) {}

  /**
   * @param events the page, newest first
   * @param next the id to ask for the page after this one with; null at the end
   */
  public record Page(List<Download> events, Long next) {}

  /** Up to {@code limit} downloads older than {@code before}, or the newest when null. */
  public Page downloads(int limit, Long before) {
    List<Download> rows =
        db.sql(
                """
                SELECT id, at, actor, action, job_id, detail::text AS detail, source_ip
                FROM audit_event
                WHERE action IN (:actions) AND (:before::bigint IS NULL OR id < :before::bigint)
                ORDER BY id DESC
                LIMIT :limit
                """)
            .param("actions", AuditAction.DOWNLOADS.stream().map(Enum::name).toList())
            .param("before", before)
            .param("limit", limit + 1)
            .query(
                (rs, row) -> {
                  JsonNode detail = json.readTree(rs.getString("detail"));
                  Timestamp at = rs.getTimestamp("at");
                  return new Download(
                      rs.getLong("id"),
                      at.toInstant(),
                      rs.getString("actor"),
                      detail.path("name").asString(rs.getString("actor")),
                      AuditAction.valueOf(rs.getString("action")),
                      rs.getObject("job_id", UUID.class),
                      strings(detail.path("namespaces")),
                      strings(detail.path("clusters")),
                      detail.hasNonNull("files") ? detail.get("files").asInt() : null,
                      detail.hasNonNull("bytes") ? detail.get("bytes").asLong() : null,
                      rs.getString("source_ip"));
                })
            .list();
    boolean more = rows.size() > limit;
    List<Download> page = more ? rows.subList(0, limit) : rows;
    return new Page(List.copyOf(page), more ? page.getLast().id() : null);
  }

  private static List<String> strings(JsonNode array) {
    return array.valueStream().map(JsonNode::asString).toList();
  }

  /** How many downloads each action accounts for, over all time. */
  public Map<AuditAction, Long> totals() {
    Map<AuditAction, Long> totals = new java.util.EnumMap<>(AuditAction.class);
    db.sql("SELECT action, count(*) AS n FROM audit_event WHERE action IN (:actions) GROUP BY action")
        .param("actions", AuditAction.DOWNLOADS.stream().map(Enum::name).toList())
        .query((rs, row) -> Map.entry(AuditAction.valueOf(rs.getString("action")), rs.getLong("n")))
        .list()
        .forEach(e -> totals.put(e.getKey(), e.getValue()));
    return totals;
  }
}
