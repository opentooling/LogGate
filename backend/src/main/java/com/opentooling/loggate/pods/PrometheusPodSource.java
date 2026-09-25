package com.opentooling.loggate.pods;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.export.SelectorBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists pods from a Prometheus-compatible API.
 *
 * <p>Loki knows a pod only as a label on its streams, so listing pods from it
 * is a scan of the index across every namespace and day asked about. A metric
 * with one series per pod, kube-state-metrics' {@code kube_pod_info} by
 * default, answers the same question from the metrics store's own index via
 * the series API, which returns label sets without reading a single sample.
 *
 * <p>The listing is over the export's own range, so a pod that ran on Tuesday
 * and was replaced on Wednesday is offered for Tuesday's export and not for
 * Thursday's. It is advice for choosing, never a grant: the export is
 * authorized on its namespaces whatever pods are named.
 *
 * <p>Answers are reused briefly, keyed to the minute, so ticking namespaces on
 * and off does not become a query per click. When the endpoint fails, the last
 * answer for the same question is reused however old, as for namespaces.
 */
public class PrometheusPodSource implements PodSource {

  /** A metric name, optionally with a matcher block of the operator's own. */
  private static final Pattern SERIES_SELECTOR =
      Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)?(\\{.*})?", Pattern.DOTALL);

  private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  /** DNS-1123 label, which is all a namespace name can be. */
  private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

  private static final int MAX_CACHED = 256;

  private final RestClient http;
  private final ObjectMapper json;
  private final LogGateProperties.Pods settings;
  private final String clusterLabel;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  private record Cached(PodListing listing, Instant fetchedAt) {}

  /**
   * @param lokiClusterLabel Loki's cluster label, used when the pod settings do
   *     not name one of their own
   * @throws IllegalStateException when the configuration cannot describe a
   *     valid query, so the application refuses to start rather than failing
   *     on every listing
   */
  public PrometheusPodSource(
      RestClient http,
      ObjectMapper json,
      LogGateProperties.Pods settings,
      String lokiClusterLabel,
      Clock clock) {
    this.http = http;
    this.json = json;
    this.settings = settings;
    this.clusterLabel =
        settings.clusterLabel().isBlank()
            ? (lokiClusterLabel == null ? "" : lokiClusterLabel)
            : settings.clusterLabel();
    this.clock = clock;

    String metric = settings.metric().trim();
    if (metric.isEmpty() || !SERIES_SELECTOR.matcher(metric).matches()) {
      throw new IllegalStateException(
          "loggate.pods.metric is not a series selector: " + settings.metric());
    }
    requireLabel("loggate.pods.pod-label", settings.podLabel());
    requireLabel("loggate.pods.namespace-label", settings.namespaceLabel());
    if (!clusterLabel.isBlank()) {
      requireLabel("loggate.pods.cluster-label", clusterLabel);
    }
    if (settings.maxPods() < 1) {
      throw new IllegalStateException("loggate.pods.max-pods must be at least 1");
    }
  }

  private static void requireLabel(String setting, String value) {
    if (!LABEL_NAME.matcher(value).matches()) {
      throw new IllegalStateException(setting + " is not a valid label name: " + value);
    }
  }

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public PodListing list(
      List<String> clusters, List<String> namespaces, Instant from, Instant to) {
    if (namespaces.isEmpty()) {
      // Every pod everywhere is the one question this must never ask.
      throw new IllegalArgumentException("pods are listed for chosen namespaces only");
    }
    String selector = selector(clusters, namespaces);
    // Keyed to the minute, so a range that moves with the clock still hits.
    long start = from.getEpochSecond() / 60 * 60;
    long end = (to.getEpochSecond() + 59) / 60 * 60;
    String key = selector + "|" + start + "|" + end;

    Instant now = clock.instant();
    Cached hit = cache.get(key);
    if (hit != null && hit.fetchedAt().plus(settings.cacheTtl()).isAfter(now)) {
      return hit.listing();
    }
    try {
      PodListing listing = fetch(selector, start, end);
      if (cache.size() >= MAX_CACHED) {
        cache.clear();
      }
      cache.put(key, new Cached(listing, now));
      return listing;
    } catch (MetricsException e) {
      if (hit != null) {
        return hit.listing();
      }
      throw e;
    }
  }

  /**
   * The operator's selector with namespace and cluster matchers added. Values
   * are escaped as regex literals, and namespaces are held to what a
   * namespace name can be, so nothing a caller sends becomes query syntax.
   */
  String selector(List<String> clusters, List<String> namespaces) {
    for (String namespace : namespaces) {
      if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
        throw new IllegalArgumentException("not a valid namespace name: " + namespace);
      }
    }
    List<String> matchers = new ArrayList<>();
    matchers.add(matcher(settings.namespaceLabel(), namespaces));
    if (!clusterLabel.isBlank() && !clusters.isEmpty()) {
      matchers.add(matcher(clusterLabel, clusters));
    }
    String added = String.join(", ", matchers);

    String metric = settings.metric().trim();
    int brace = metric.indexOf('{');
    if (brace < 0) {
      return metric + "{" + added + "}";
    }
    String inner = metric.substring(brace + 1, metric.length() - 1).trim();
    return metric.substring(0, brace) + "{" + (inner.isEmpty() ? "" : inner + ", ") + added + "}";
  }

  private static String matcher(String label, List<String> values) {
    List<String> sorted = values.stream().distinct().sorted().toList();
    return label
        + "=~\""
        + String.join("|", sorted.stream().map(SelectorBuilder::regexLiteral).toList())
        + "\"";
  }

  private PodListing fetch(String selector, long start, long end) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("match", selector);
    params.put("start", Long.toString(start));
    params.put("end", Long.toString(end));
    // One past the maximum, so a listing that was cut short can say so.
    // Servers too old to know the parameter ignore it; the cut is made here too.
    params.put("limit", Integer.toString(settings.maxPods() + 1));

    String body;
    try {
      body =
          http.get()
              .uri(
                  builder -> {
                    builder.path("/api/v1/series");
                    builder.queryParam("match[]", "{match}");
                    builder.queryParam("start", "{start}");
                    builder.queryParam("end", "{end}");
                    builder.queryParam("limit", "{limit}");
                    return builder.build(params);
                  })
              .headers(
                  headers -> {
                    if (!settings.tenantId().isBlank()) {
                      headers.set("X-Scope-OrgID", settings.tenantId());
                    }
                    String token = bearerToken();
                    if (!token.isEmpty()) {
                      headers.setBearerAuth(token);
                    }
                  })
              .retrieve()
              .body(String.class);
    } catch (RestClientException e) {
      throw new MetricsException("the metrics endpoint did not answer: " + e.getMessage(), e);
    }
    return parse(body);
  }

  /** Re-read on every request, so a rotated service-account token is picked up. */
  private String bearerToken() {
    if (settings.bearerTokenFile().isBlank()) {
      return "";
    }
    try {
      return Files.readString(Path.of(settings.bearerTokenFile())).trim();
    } catch (IOException e) {
      throw new MetricsException("could not read the metrics bearer token", e);
    }
  }

  PodListing parse(String body) {
    JsonNode root;
    try {
      root = json.readTree(body == null ? "" : body);
    } catch (JacksonException e) {
      throw new MetricsException("the metrics endpoint did not return JSON", e);
    }
    if (root == null || !"success".equals(root.path("status").asString(""))) {
      throw new MetricsException(
          "the metrics endpoint refused the query: " + root.path("error").asString("no reason given"));
    }
    Set<PodInfo> pods = new LinkedHashSet<>();
    for (JsonNode series : root.path("data")) {
      String namespace = series.path(settings.namespaceLabel()).asString("");
      String pod = series.path(settings.podLabel()).asString("");
      if (!namespace.isEmpty() && !pod.isEmpty()) {
        pods.add(new PodInfo(namespace, pod));
      }
    }
    List<PodInfo> sorted =
        pods.stream()
            .sorted(Comparator.comparing(PodInfo::namespace).thenComparing(PodInfo::name))
            .toList();
    boolean truncated = sorted.size() > settings.maxPods();
    return new PodListing(
        true, truncated ? sorted.subList(0, settings.maxPods()) : sorted, truncated);
  }
}
