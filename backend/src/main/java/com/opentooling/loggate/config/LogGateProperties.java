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
    @DefaultValue Namespaces namespaces,
    @DefaultValue Loki loki,
    @DefaultValue Windows windows,
    @DefaultValue Quotas quotas,
    @DefaultValue Execution execution,
    @DefaultValue Storage storage) {

  /**
   * @param maxRange longest range one export may cover
   * @param maxEstimatedBytes largest export that may be admitted
   * @param concurrentPerUser exports one person may have in flight
   * @param concurrentGlobal exports the platform may have in flight
   * @param byteLimitHeadroom multiplier over the estimate for the runtime cap,
   *     because a live namespace keeps receiving logs while the export runs
   * @param minimumByteLimit floor for the runtime cap, so small exports are not
   *     failed by a tiny estimate
   */
  public record Quotas(
      @DefaultValue("2d") Duration maxRange,
      @DefaultValue("53687091200") long maxEstimatedBytes,
      @DefaultValue("2") int concurrentPerUser,
      @DefaultValue("10") int concurrentGlobal,
      @DefaultValue("1.25") double byteLimitHeadroom,
      @DefaultValue("67108864") long minimumByteLimit) {}

  /**
   * @param enabled whether this pod runs workers at all
   * @param workers how many windows may run at once in one pod
   * @param lease how long a worker holds a window before it can be reclaimed
   * @param maxAttempts attempts per window before giving up on the job
   * @param idlePause how long a worker waits when the queue is empty
   * @param retention how long artifacts live once a job is ready
   */
  public record Execution(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("4") int workers,
      @DefaultValue("2m") Duration lease,
      @DefaultValue("3") int maxAttempts,
      @DefaultValue("2s") Duration idlePause,
      @DefaultValue("48h") Duration retention) {}

  /**
   * @param endpoint S3-compatible endpoint
   * @param region region to sign with
   * @param bucket bucket holding export artifacts
   * @param accessKey access key
   * @param secretKey secret key
   * @param pathStyle whether to use path-style addressing, which MinIO needs
   * @param publicEndpoint endpoint a browser can reach, when it differs from
   *     the in-cluster one; presigned URLs are signed against this
   * @param presignedUrlLifetime how long a download link stays valid
   */
  public record Storage(
      @DefaultValue("http://minio.observability.svc.cluster.local:9000") String endpoint,
      @DefaultValue("us-east-1") String region,
      @DefaultValue("loggate-exports") String bucket,
      @DefaultValue("") String accessKey,
      @DefaultValue("") String secretKey,
      @DefaultValue("true") boolean pathStyle,
      @DefaultValue("") String publicEndpoint,
      @DefaultValue("30m") Duration presignedUrlLifetime) {}

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
