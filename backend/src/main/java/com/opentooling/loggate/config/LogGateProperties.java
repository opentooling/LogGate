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
 * @param oidc identity provider settings
 * @param pods where pod names are listed from, since Loki cannot say
 */
@ConfigurationProperties("loggate")
public record LogGateProperties(
    @DefaultValue Namespaces namespaces,
    @DefaultValue Loki loki,
    @DefaultValue Windows windows,
    @DefaultValue Quotas quotas,
    @DefaultValue Execution execution,
    @DefaultValue Storage storage,
    @DefaultValue Access access,
    @DefaultValue Oidc oidc,
    @DefaultValue Pods pods) {

  /** How callers are granted namespaces. */
  public enum AccessMode {
    /**
     * A namespace belongs to the team named by a label on it, read from the
     * Kubernetes API, and only that team's group may export it.
     */
    TEAM_LABEL,
    /**
     * Every namespace Loki holds logs for is available to anyone holding
     * {@code openRole}. Nothing is read from the Kubernetes API, so this works
     * when the logs come from clusters LogGate cannot reach.
     */
    OPEN
  }

  /**
   * @param mode how callers are granted namespaces
   * @param openRole in open mode, the client role on LogGate's own OIDC client
   *     that a caller must hold, directly or through a group, to see or export
   *     anything. Required in open mode: there is no setting that opens every
   *     log to every signed-in user
   * @param discoveryWindow in open mode, how far back to look in Loki for the
   *     clusters and namespaces to offer
   * @param discoveryCacheTtl how long a discovered list is reused before Loki
   *     is asked again
   * @param adminRole the client role on LogGate's own OIDC client that lets
   *     someone see the activity dashboard and the audit trail, across
   *     everyone's exports, in either mode; blank makes nobody an administrator
   */
  public record Access(
      @DefaultValue("TEAM_LABEL") AccessMode mode,
      @DefaultValue("") String openRole,
      @DefaultValue("7d") Duration discoveryWindow,
      @DefaultValue("60s") Duration discoveryCacheTtl,
      @DefaultValue("loggate-admin") String adminRole) {

    /** Whether callers are granted namespaces by team label. */
    public boolean teamLabel() {
      return mode == AccessMode.TEAM_LABEL;
    }
  }

  /**
   * @param maxRange longest range one export may cover
   * @param maxEstimatedBytes largest export that may be admitted
   * @param concurrentPerUser exports one person may have in flight
   * @param concurrentGlobal exports the platform may have in flight
   * @param byteLimitHeadroom multiplier over the estimate for the runtime cap,
   *     because a live namespace keeps receiving logs while the export runs
   * @param minimumByteLimit floor for the runtime cap, so small exports are not
   *     failed by a tiny estimate
   * @param dailyBytesPerTeam how much one team may export within
   *     {@code budgetWindow}; zero or less switches the budget off
   * @param budgetWindow the rolling window the budget is measured over
   */
  public record Quotas(
      @DefaultValue("2d") Duration maxRange,
      @DefaultValue("53687091200") long maxEstimatedBytes,
      @DefaultValue("2") int concurrentPerUser,
      @DefaultValue("10") int concurrentGlobal,
      @DefaultValue("1.25") double byteLimitHeadroom,
      @DefaultValue("67108864") long minimumByteLimit,
      @DefaultValue("536870912000") long dailyBytesPerTeam,
      @DefaultValue("24h") Duration budgetWindow) {}

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
   * @param pathStyle whether to use path-style addressing, which self-hosted
   *     stores need unless DNS is set up for virtual-hosted buckets
   * @param publicEndpoint endpoint a browser can reach, when it differs from
   *     the in-cluster one; presigned URLs are signed against this
   * @param presignedUrlLifetime how long a download link stays valid
   * @param checksums when the SDK adds integrity checksums to requests. AWS
   *     accepts them always; many S3-compatible stores, ONTAP S3 among them,
   *     do not document the trailing-checksum encoding the SDK uses by default,
   *     so the default is to send one only when the operation requires it
   * @param caCertificate path to a PEM file of certificates to trust for the
   *     storage endpoint, for stores behind an internal certificate authority;
   *     empty uses the JVM's default trust
   */
  public record Storage(
      @DefaultValue("http://s3.observability.svc.cluster.local:9000") String endpoint,
      @DefaultValue("us-east-1") String region,
      @DefaultValue("loggate-exports") String bucket,
      @DefaultValue("") String accessKey,
      @DefaultValue("") String secretKey,
      @DefaultValue("true") boolean pathStyle,
      @DefaultValue("") String publicEndpoint,
      @DefaultValue("30m") Duration presignedUrlLifetime,
      @DefaultValue("WHEN_REQUIRED") Checksums checksums,
      @DefaultValue("") String caCertificate) {

    /** When integrity checksums are added to storage requests. */
    public enum Checksums {
      /** Only when the operation demands one. Compatible with most stores. */
      WHEN_REQUIRED,
      /** Whenever the operation supports one. AWS's default; AWS S3 only. */
      WHEN_SUPPORTED
    }
  }

  /**
   * @param enabled whether to resolve namespaces from the Kubernetes API; when
   *     false the catalog is permanently unready and every export is refused
   * @param labelKey namespace label naming the owning team, e.g. {@code xyz.com/team}
   * @param groupTemplate template rendering the owning group from the team, with
   *     {@code {team}} and {@code {env}} placeholders, e.g. {@code ad-{team}-{env}}
   * @param environment value substituted for {@code {env}}
   * @param cluster this cluster's name in Loki's cluster label. The Kubernetes
   *     API can only vouch for its own cluster's namespaces, so in team-label
   *     mode every export is pinned to it; required when a cluster label is set
   */
  public record Namespaces(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("xyz.com/team") String labelKey,
      @DefaultValue("ad-{team}-{env}") String groupTemplate,
      @DefaultValue("dev") String environment,
      @DefaultValue("") String cluster) {}

  /**
   * @param url base URL of the Loki read path, usually its gateway
   * @param tenantId value sent as {@code X-Scope-OrgID}; exports should use
   *     their own tenant so a large extraction cannot starve interactive queries
   * @param queryLimit entries per {@code query_range} page, which must not
   *     exceed Loki's own {@code max_entries_limit_per_query}
   * @param timeout per-request timeout
   * @param clusterLabel the stream label naming the cluster a log came from,
   *     when Loki holds more than one cluster's logs; empty when it does not
   */
  public record Loki(
      @DefaultValue("http://loki-gateway.observability.svc.cluster.local") String url,
      @DefaultValue("") String tenantId,
      @DefaultValue("5000") int queryLimit,
      @DefaultValue("60s") Duration timeout,
      @DefaultValue("") String clusterLabel) {

    /** Whether logs are told apart by cluster. */
    public boolean hasClusters() {
      return !clusterLabel.isBlank();
    }
  }

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

  /**
   * @param caCertificate path to a PEM file of certificates to trust for the
   *     OIDC issuer endpoint, for identity providers behind an internal
   *     certificate authority; empty uses the JVM's default trust
   */
  public record Oidc(
      @DefaultValue("") String caCertificate) {}

  /**
   * Where the pods on offer are listed from.
   *
   * <p>Loki's index knows pods only as a label on streams, and asking it for a
   * label's values across many namespaces and days is a scan. Any
   * Prometheus-compatible API that holds a per-pod series, kube-state-metrics'
   * {@code kube_pod_info} being the usual one, answers the same question from
   * its own index for a fraction of the cost.
   *
   * @param metricsUrl base URL of a Prometheus-compatible query API
   *     (Prometheus, Thanos, Mimir, OpenShift's thanos-querier); empty turns
   *     pod listing off, leaving the pod pattern as the only way to narrow
   * @param metric the series selector listing one series per pod, e.g.
   *     {@code kube_pod_info} or {@code kube_pod_info{job="kube-state-metrics"}}
   * @param podLabel the label on that series naming the pod
   * @param namespaceLabel the label naming the pod's namespace
   * @param clusterLabel the label naming the pod's cluster; empty uses
   *     {@code loggate.loki.cluster-label}, since the same shipper usually
   *     stamps both
   * @param tenantId sent as {@code X-Scope-OrgID}, for Mimir and Cortex
   * @param bearerTokenFile a file holding a bearer token, re-read on every
   *     request so a rotated service-account token is picked up
   * @param caCertificate a PEM file of certificates to trust for the endpoint
   * @param timeout per-request timeout
   * @param maxPods most pods returned for one listing
   * @param cacheTtl how long one listing is reused
   * @param allowPattern whether pods may be matched by a glob as well as
   *     picked by name. Off, an export names its pods from the list or takes
   *     every pod, and a request carrying a pattern is refused
   */
  public record Pods(
      @DefaultValue("") String metricsUrl,
      @DefaultValue("kube_pod_info") String metric,
      @DefaultValue("pod") String podLabel,
      @DefaultValue("namespace") String namespaceLabel,
      @DefaultValue("") String clusterLabel,
      @DefaultValue("") String tenantId,
      @DefaultValue("") String bearerTokenFile,
      @DefaultValue("") String caCertificate,
      @DefaultValue("10s") Duration timeout,
      @DefaultValue("1000") int maxPods,
      @DefaultValue("60s") Duration cacheTtl,
      @DefaultValue("true") boolean allowPattern) {

    /** Whether pods can be listed at all. */
    public boolean enabled() {
      return !metricsUrl.isBlank();
    }
  }
}
