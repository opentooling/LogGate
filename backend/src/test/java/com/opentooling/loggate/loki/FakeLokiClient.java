package com.opentooling.loggate.loki;

import com.opentooling.loggate.export.Timestamps;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory Loki that behaves like the real one where it matters.
 *
 * <p>It honours an <em>inclusive</em> start, an exclusive end, and the page
 * limit, so the overlap that makes paging hard is reproduced rather than
 * scripted. A pager that is wrong about the nanosecond boundary will fail here
 * for the same reason it would fail in production.
 */
public class FakeLokiClient implements LokiClient {

  private final List<LogEntry> entries = new ArrayList<>();
  private final Deque<RuntimeException> faults = new ArrayDeque<>();
  private final List<String> selectorsSeen = new ArrayList<>();
  private final Map<String, Long> volume = new LinkedHashMap<>();
  private int queries;

  /** Adds an entry at {@code nanos} from a given pod. */
  public FakeLokiClient entry(long nanos, String pod, String line) {
    entries.add(new LogEntry(nanos, line, Map.of("namespace", "platform-dev", "pod", pod)));
    entries.sort(Comparator.comparingLong(LogEntry::timestampNanos));
    return this;
  }

  /** Adds {@code count} entries all sharing one nanosecond. */
  public FakeLokiClient entriesAtSameNanos(long nanos, int count) {
    for (int i = 0; i < count; i++) {
      entry(nanos, "api-0", "line-" + nanos + "-" + i);
    }
    return this;
  }

  /** Makes the next query fail with {@code fault}. */
  public FakeLokiClient failNextQuery(RuntimeException fault) {
    faults.add(fault);
    return this;
  }

  /** Sets what the volume API reports for a namespace. */
  public FakeLokiClient volume(String namespace, long bytes) {
    volume.put(namespace, bytes);
    return this;
  }

  private final Map<String, List<String>> labelValues = new LinkedHashMap<>();
  private final List<String> labelQueries = new ArrayList<>();

  /**
   * Sets the values of {@code label} among streams matching {@code selector};
   * an empty selector is the answer for every stream.
   */
  public FakeLokiClient labelValues(String label, String selector, String... values) {
    labelValues.put(label + "|" + selector, List.of(values));
    return this;
  }

  /** The label discovery calls made, as {@code label|selector}. */
  public List<String> labelQueries() {
    return List.copyOf(labelQueries);
  }

  @Override
  public List<String> labelValues(String label, String selector, Instant from, Instant to) {
    String key = label + "|" + (selector == null ? "" : selector);
    labelQueries.add(key);
    RuntimeException fault = faults.poll();
    if (fault != null) {
      throw fault;
    }
    return labelValues.getOrDefault(key, List.of());
  }

  /** How many query_range calls were made. */
  public int queries() {
    return queries;
  }

  /** The selectors the client was asked for, in order. */
  public List<String> selectorsSeen() {
    return List.copyOf(selectorsSeen);
  }

  private final Map<String, Long> samples = new LinkedHashMap<>();

  /** Sets what a sample of {@code query} reports. */
  public FakeLokiClient sample(String query, long bytes) {
    samples.put(query, bytes);
    return this;
  }

  @Override
  public java.util.OptionalLong sampleBytes(
      String query, Instant at, java.time.Duration window) {
    Long bytes = samples.get(query);
    return bytes == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(bytes);
  }

  @Override
  public VolumeEstimate volume(String selector, Instant from, Instant to) {
    selectorsSeen.add(selector);
    return volume.isEmpty() ? VolumeEstimate.empty() : new VolumeEstimate(Map.copyOf(volume));
  }

  @Override
  public QueryPage queryRange(String selector, Instant from, Instant to, int limit) {
    queries++;
    if (!faults.isEmpty()) {
      throw faults.poll();
    }
    long start = Timestamps.toNanos(from);
    long end = Timestamps.toNanos(to);
    List<LogEntry> page =
        entries.stream()
            // Start is inclusive and end is exclusive, exactly as Loki does it.
            .filter(entry -> entry.timestampNanos() >= start && entry.timestampNanos() < end)
            .limit(limit)
            .toList();
    return new QueryPage(page, limit);
  }
}
