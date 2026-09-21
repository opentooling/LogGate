package com.opentooling.loggate.export;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * A structured export request. There is deliberately no LogQL here: the
 * selector is generated from these fields server-side.
 *
 * @param namespaces namespaces to export from; every one is authorized. Whether
 *     an empty list is allowed, meaning every namespace, depends on the access
 *     mode, so it is decided there rather than here
 * @param podPattern optional glob over pod names, e.g. {@code api-*}
 * @param containerPattern optional glob over container names
 * @param lineFilter optional literal substring the line must contain
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 * @param clusters clusters to export from, when Loki holds more than one;
 *     empty means every cluster the caller may read
 */
public record ExportRequest(
    @Size(max = 50) List<@NotEmpty String> namespaces,
    String podPattern,
    String containerPattern,
    String lineFilter,
    @NotNull Instant from,
    @NotNull Instant to,
    @Size(max = 20) List<@NotEmpty String> clusters) {

  public ExportRequest {
    // Copied without List.copyOf, which throws on a null element: a null here
    // is a malformed request, and validation should get to say so with a 400
    // rather than the constructor failing first.
    namespaces = namespaces == null ? List.of() : unmodifiable(namespaces);
    clusters = clusters == null ? List.of() : unmodifiable(clusters);
  }

  private static List<String> unmodifiable(List<String> values) {
    return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
  }

  /** A request with no cluster dimension, as every request was before there was one. */
  public ExportRequest(
      List<String> namespaces,
      String podPattern,
      String containerPattern,
      String lineFilter,
      Instant from,
      Instant to) {
    this(namespaces, podPattern, containerPattern, lineFilter, from, to, List.of());
  }

  /** The same request, restricted to {@code clusters}. */
  public ExportRequest withClusters(List<String> clusters) {
    return new ExportRequest(
        namespaces, podPattern, containerPattern, lineFilter, from, to, clusters);
  }

  /** The range's duration. */
  public java.time.Duration duration() {
    return java.time.Duration.between(from, to);
  }

  /** Whether the range is the right way round and non-empty. */
  public boolean hasValidRange() {
    return to.isAfter(from);
  }
}
