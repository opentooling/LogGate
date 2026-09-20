package com.opentooling.loggate.loki;

import com.opentooling.loggate.config.LogGateProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Loki read API over HTTP.
 *
 * <p>Retries are bounded and back off, honouring {@code Retry-After} when Loki
 * sends it. The intent is that a badly sized export degrades itself rather than
 * becoming an incident for everyone reading dashboards: exports are expected to
 * run against their own tenant or read pool, and to slow down when told to.
 */
public class HttpLokiClient implements LokiClient {

  private static final Logger log = LoggerFactory.getLogger(HttpLokiClient.class);

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private final RestClient http;
  private final ObjectMapper json;
  private final String tenantId;
  private final Sleeper sleeper;
  private final java.util.function.Consumer<String> onThrottle;

  /** Pauses between retries. Injected so tests do not actually wait. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  public HttpLokiClient(
      RestClient http, ObjectMapper json, LogGateProperties properties, Sleeper sleeper) {
    this(http, json, properties, sleeper, reason -> {});
  }

  /**
   * @param onThrottle called when Loki pushes back, so back-pressure is
   *     visible as a metric rather than only as a slow export
   */
  public HttpLokiClient(
      RestClient http,
      ObjectMapper json,
      LogGateProperties properties,
      Sleeper sleeper,
      java.util.function.Consumer<String> onThrottle) {
    this.http = http;
    this.json = json;
    this.tenantId = properties.loki().tenantId();
    this.sleeper = sleeper;
    this.onThrottle = onThrottle;
  }

  @Override
  public VolumeEstimate volume(String selector, Instant from, Instant to) {
    JsonNode body =
        get(
            "/loki/api/v1/index/volume",
            new LinkedHashMap<>(
                Map.of("query", selector, "start", nanos(from), "end", nanos(to))));

    Map<String, Long> byNamespace = new LinkedHashMap<>();
    for (JsonNode result : body.path("data").path("result")) {
      String namespace = result.path("metric").path("namespace").asString();
      JsonNode value = result.path("value");
      if (namespace.isEmpty() || !value.isArray() || value.size() < 2) {
        continue;
      }
      byNamespace.merge(namespace, parseBytes(value.get(1).asString()), Long::sum);
    }
    return byNamespace.isEmpty() ? VolumeEstimate.empty() : new VolumeEstimate(byNamespace);
  }

  @Override
  public QueryPage queryRange(String selector, Instant from, Instant to, int limit) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("query", selector);
    params.put("start", nanos(from));
    params.put("end", nanos(to));
    params.put("limit", Integer.toString(limit));
    params.put("direction", "forward");
    JsonNode body = get("/loki/api/v1/query_range", params);

    List<LogEntry> entries = new ArrayList<>();
    for (JsonNode stream : body.path("data").path("result")) {
      Map<String, String> labels = labelsOf(stream.path("stream"));
      for (JsonNode value : stream.path("values")) {
        if (!value.isArray() || value.size() < 2) {
          continue;
        }
        entries.add(
            new LogEntry(parseNanos(value.get(0).asString()), value.get(1).asString(), labels));
      }
    }
    // Loki returns one array per stream; the pager needs a single ordering.
    entries.sort(Comparator.comparingLong(LogEntry::timestampNanos));
    return new QueryPage(List.copyOf(entries), limit);
  }

  /**
   * Issues a GET, passing every query value as a URI variable.
   *
   * <p>That indirection is not stylistic: a LogQL selector always contains
   * braces, and braces in a URI template are expansion syntax. Interpolating
   * the selector directly would fail on every real query.
   */
  private JsonNode get(String path, Map<String, Object> params) {
    Duration backoff = INITIAL_BACKOFF;
    RuntimeException last = null;

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        String body =
            http.get()
                .uri(
                    uriBuilder -> {
                      uriBuilder.path(path);
                      params.keySet().forEach(name -> uriBuilder.queryParam(name, "{" + name + "}"));
                      return uriBuilder.build(params);
                    })
                .headers(
                    headers -> {
                      if (!tenantId.isBlank()) {
                        headers.set("X-Scope-OrgID", tenantId);
                      }
                    })
                .retrieve()
                .body(String.class);
        return json.readTree(body == null ? "{}" : body);
      } catch (RestClientResponseException e) {
        if (!isRetryable(e.getStatusCode())) {
          throw new LokiException(
              "Loki returned %d: %s".formatted(e.getStatusCode().value(), e.getStatusText()), e);
        }
        last = e;
        onThrottle.accept(Integer.toString(e.getStatusCode().value()));
        Duration wait = retryAfter(e.getResponseHeaders()).orElse(backoff);
        log.warn(
            "Loki returned {}, retrying in {} (attempt {}/{})",
            e.getStatusCode().value(),
            wait,
            attempt,
            MAX_ATTEMPTS);
        pause(wait);
        backoff = nextBackoff(backoff);
      }
    }
    throw new LokiException("Loki did not succeed after " + MAX_ATTEMPTS + " attempts", last);
  }

  /** 429 and 5xx are worth retrying; anything else is our fault, not load. */
  private static boolean isRetryable(HttpStatusCode status) {
    return status.value() == 429 || status.is5xxServerError();
  }

  /** Package-private so the header handling can be tested without a server. */
  static java.util.Optional<Duration> retryAfter(org.springframework.http.HttpHeaders headers) {
    String header = headers == null ? null : headers.getFirst("Retry-After");
    if (header == null) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(Duration.ofSeconds(Long.parseLong(header.trim())));
    } catch (NumberFormatException ignored) {
      // A date-formatted Retry-After is valid HTTP but not worth parsing here;
      // the exponential backoff is a safe fallback.
      return java.util.Optional.empty();
    }
  }

  private static Duration nextBackoff(Duration current) {
    Duration doubled = current.multipliedBy(2);
    return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
  }

  private void pause(Duration duration) {
    try {
      sleeper.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LokiException("interrupted while backing off from Loki", e);
    }
  }

  private static Map<String, String> labelsOf(JsonNode stream) {
    Map<String, String> labels = new HashMap<>();
    stream.propertyStream().forEach(entry -> labels.put(entry.getKey(), entry.getValue().asString()));
    return Map.copyOf(labels);
  }

  private static long parseNanos(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new LokiException("Loki returned a timestamp that is not a number: " + value, e);
    }
  }

  private static long parseBytes(String value) {
    try {
      // The volume API reports bytes as a decimal string.
      return (long) Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new LokiException("Loki returned a volume that is not a number: " + value, e);
    }
  }

  private static String nanos(Instant instant) {
    return Long.toString(
        Math.addExact(
            Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano()));
  }
}
