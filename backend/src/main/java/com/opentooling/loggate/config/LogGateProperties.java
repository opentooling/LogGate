package com.opentooling.loggate.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for namespace authorization, the Loki read path, and how an
 * export's range is divided into windows.
 *
 * @param namespaces namespace-to-team-to-group resolution settings
 * @param loki the upstream Loki read endpoint
 * @param windows how an export's time range is divided
 */
@ConfigurationProperties("loggate")
public record LogGateProperties(
    @DefaultValue Namespaces namespaces, @DefaultValue Loki loki, @DefaultValue Windows windows) {

  /**
   * @param enabled whether to resolve namespaces from the Kubernetes API; when
   *     false the catalog is permanently unready and every export is refused
   * @param labelKey namespace label naming the owning team, e.g. {@code xyz.com/team}
   * @param groupTemplate template rendering the owning group from the team, with
   *     {@code {team}} and {@code {env}} placeholders, e.g. {@code ad-{team}-{env}}
   * @param environment value substituted for {@code {env}}
   */
  public record Namespaces(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("xyz.com/team") String labelKey,
      @DefaultValue("ad-{team}-{env}") String groupTemplate,
      @DefaultValue("dev") String environment) {}

  /**
   * @param url base URL of the Loki read path, usually its gateway
   * @param tenantId value sent as {@code X-Scope-OrgID}; exports should use
   *     their own tenant so a large extraction cannot starve interactive queries
   * @param queryLimit entries per {@code query_range} page, which must not
   *     exceed Loki's own {@code max_entries_limit_per_query}
   * @param timeout per-request timeout
   */
  public record Loki(
      @DefaultValue("http://loki-gateway.observability.svc.cluster.local") String url,
      @DefaultValue("") String tenantId,
      @DefaultValue("5000") int queryLimit,
      @DefaultValue("60s") Duration timeout) {}

  /**
   * @param targetBytes bytes a single window should produce; sized so parts
   *     stay above the 5 MiB minimum for server-side concatenation while still
   *     giving useful progress granularity
   * @param minDuration shortest a window may be, whatever the volume
   * @param maxDuration longest a window may be
   * @param maxCount refuse to plan more windows than this
   */
  public record Windows(
      @DefaultValue("268435456") long targetBytes,
      @DefaultValue("1m") Duration minDuration,
      @DefaultValue("1h") Duration maxDuration,
      @DefaultValue("5000") int maxCount) {}
}
