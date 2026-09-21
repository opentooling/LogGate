package com.opentooling.loggate.namespaces;

import com.opentooling.loggate.export.SelectorBuilder;
import com.opentooling.loggate.loki.LokiClient;
import com.opentooling.loggate.loki.LokiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The clusters and namespaces Loki holds logs for.
 *
 * <p>This is where open mode learns what exists, in place of the Kubernetes
 * API: Loki has logs from every cluster that ships to it, including clusters
 * LogGate has no network path to. Answers come from Loki's index, so they cost
 * no log reads, and are reused for a short while so that opening the page does
 * not become a query.
 *
 * <p>When Loki fails, the last answer is reused however old it is: a list that
 * is a minute stale is more useful than an error, and in open mode the list is
 * a convenience for choosing, not the thing that grants access.
 */
public class LokiDirectory {

  /** Enough for every combination anyone will pick, without growing forever. */
  private static final int MAX_CACHED = 256;

  private final LokiClient loki;
  private final String clusterLabel;
  private final Duration window;
  private final Duration ttl;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  private record Cached(List<String> values, Instant fetchedAt) {}

  public LokiDirectory(
      LokiClient loki, String clusterLabel, Duration window, Duration ttl, Clock clock) {
    this.loki = loki;
    this.clusterLabel = clusterLabel == null ? "" : clusterLabel;
    this.window = window;
    this.ttl = ttl;
    this.clock = clock;
  }

  /** Every cluster with logs in the window; none when there is no cluster label. */
  public List<String> clusters() {
    if (clusterLabel.isBlank()) {
      return List.of();
    }
    return cached("clusters", "", () -> query(clusterLabel, ""));
  }

  /** Every namespace with logs in the window, within {@code clusters} when any are given. */
  public List<String> namespaces(List<String> clusters) {
    String selector =
        clusterLabel.isBlank() || clusters.isEmpty()
            ? ""
            : SelectorBuilder.clusterSelector(clusterLabel, clusters.stream().distinct().sorted().toList());
    return cached("namespaces", selector, () -> query("namespace", selector));
  }

  private List<String> query(String label, String selector) {
    Instant now = clock.instant();
    return loki.labelValues(label, selector, now.minus(window), now).stream().sorted().toList();
  }

  private List<String> cached(String kind, String selector, Supplier<List<String>> fetch) {
    String key = kind + "|" + selector;
    Instant now = clock.instant();
    Cached hit = cache.get(key);
    if (hit != null && hit.fetchedAt().plus(ttl).isAfter(now)) {
      return hit.values();
    }
    try {
      List<String> values = fetch.get();
      if (cache.size() >= MAX_CACHED) {
        cache.clear();
      }
      cache.put(key, new Cached(values, now));
      return values;
    } catch (LokiException e) {
      if (hit != null) {
        return hit.values();
      }
      throw e;
    }
  }
}
