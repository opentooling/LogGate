package com.opentooling.loggate.audit;

import java.sql.Array;
import java.sql.SQLException;
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
 * offset, so a page does not shift as new events are recorded above it.
 */
public class AuditLog {

  /** Which part of the trail to read. */
  public enum Kind {
    ALL(List.of(AuditAction.values())),
    EXPORTS(AuditAction.EXPORTS),
    DOWNLOADS(AuditAction.DOWNLOADS),
    DENIALS(AuditAction.DENIALS);

    private final List<AuditAction> actions;

    Kind(List<AuditAction> actions) {
      this.actions = actions;
    }

    public List<AuditAction> actions() {
      return actions;
    }
  }

  private final JdbcClient db;
  private final ObjectMapper json;

  public AuditLog(JdbcClient db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  /**
   * One thing that happened.
   *
   * @param id the event's id, which pages are keyed on
   * @param at when it happened
   * @param subject who, by their stable OIDC subject
   * @param name who, by the name they signed in with at the time
   * @param action what happened
   * @param jobId which export, when it was about one
   * @param namespaces the export's namespaces, or those asked for; empty meaning every one
   * @param clusters the export's clusters, when Loki holds several
   * @param files how many files, when links were issued or an export completed
   * @param bytes how much was written, uncompressed
   * @param estimatedBytes how much was expected, when an export was asked for
   * @param note why, for a refusal, a failure or a denial
   * @param sourceIp where the request came from; none for what a worker did
   */
  public record Event(
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
      Long estimatedBytes,
      String note,
      String sourceIp) {}

  /**
   * @param events the page, newest first
   * @param next the id to ask for the page after this one with; null at the end
   */
  public record Page(List<Event> events, Long next) {}

  /**
   * Up to {@code limit} events of one kind older than {@code before}, or the
   * newest when null.
   *
   * <p>What an event's own detail leaves out is filled from its export: older
   * events kept the export's id only in their detail, and some never carried
   * the name or the namespaces at all.
   */
  public Page events(Kind kind, int limit, Long before) {
    List<Event> rows =
        db.sql(
                """
                SELECT e.id, e.at, e.actor, e.action, e.detail::text AS detail, e.source_ip,
                       k.job_id, j.requested_by_name, j.namespaces, j.clusters
                FROM audit_event e
                CROSS JOIN LATERAL (
                  SELECT coalesce(e.job_id,
                    CASE WHEN e.detail->>'jobId' ~* '^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$'
                         THEN (e.detail->>'jobId')::uuid END) AS job_id) k
                LEFT JOIN export_job j ON j.id = k.job_id
                WHERE e.action IN (:actions) AND (:before::bigint IS NULL OR e.id < :before::bigint)
                ORDER BY e.id DESC
                LIMIT :limit
                """)
            .param("actions", kind.actions().stream().map(Enum::name).toList())
            .param("before", before)
            .param("limit", limit + 1)
            .query(
                (rs, row) -> {
                  JsonNode detail = json.readTree(rs.getString("detail"));
                  AuditAction action = AuditAction.valueOf(rs.getString("action"));
                  Timestamp at = rs.getTimestamp("at");
                  String actor = rs.getString("actor");
                  String jobName = rs.getString("requested_by_name");
                  List<String> namespaces =
                      detail.has("namespaces")
                          ? strings(detail.get("namespaces"))
                          : detail.has("requested")
                              ? strings(detail.get("requested"))
                              : array(rs.getArray("namespaces"));
                  return new Event(
                      rs.getLong("id"),
                      at.toInstant(),
                      actor,
                      detail.path("name").asString(jobName == null ? actor : jobName),
                      action,
                      rs.getObject("job_id", UUID.class),
                      namespaces,
                      detail.has("clusters")
                          ? strings(detail.get("clusters"))
                          : array(rs.getArray("clusters")),
                      detail.hasNonNull("files") ? detail.get("files").asInt() : null,
                      detail.hasNonNull("bytes") ? detail.get("bytes").asLong() : null,
                      detail.hasNonNull("estimatedBytes")
                          ? detail.get("estimatedBytes").asLong()
                          : null,
                      note(action, detail),
                      rs.getString("source_ip"));
                })
            .list();
    boolean more = rows.size() > limit;
    List<Event> page = more ? rows.subList(0, limit) : rows;
    return new Page(List.copyOf(page), more ? page.getLast().id() : null);
  }

  /** Why, in a line: what a refusal, failure or denial recorded. */
  private static String note(AuditAction action, JsonNode detail) {
    if (action == AuditAction.NAMESPACE_ACCESS_DENIED && detail.path("denied").isObject()) {
      return "Refused " + String.join(", ", detail.get("denied").propertyNames());
    }
    return detail.hasNonNull("reason") ? detail.get("reason").asString() : null;
  }

  private static List<String> strings(JsonNode array) {
    return array.valueStream().map(JsonNode::asString).toList();
  }

  private static List<String> array(Array array) throws SQLException {
    return array == null ? List.of() : List.of((String[]) array.getArray());
  }

  /** How many times each action happened, over all time. */
  public Map<AuditAction, Long> totals() {
    Map<AuditAction, Long> totals = new java.util.EnumMap<>(AuditAction.class);
    db.sql("SELECT action, count(*) AS n FROM audit_event GROUP BY action")
        .query((rs, row) -> Map.entry(rs.getString("action"), rs.getLong("n")))
        .list()
        .forEach(
            e -> {
              // A row written by a newer version, rolled back from, is skipped
              // rather than failing the page.
              try {
                totals.put(AuditAction.valueOf(e.getKey()), e.getValue());
              } catch (IllegalArgumentException unknown) {
                // ignored
              }
            });
    return totals;
  }
}
