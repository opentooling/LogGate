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
 * @param pods pods to export from, picked by name from a list; empty means
 *     every pod, or those {@code podPattern} matches. Not both
 * @param format what the files hold; JSON when not given
 */
public record ExportRequest(
    @Size(max = 50) List<@NotEmpty String> namespaces,
    String podPattern,
    String containerPattern,
    String lineFilter,
    @NotNull Instant from,
    @NotNull Instant to,
    @Size(max = 20) List<@NotEmpty String> clusters,
    @Size(max = 200) List<@NotEmpty String> pods,
    OutputFormat format) {

  public ExportRequest {
    // Copied without List.copyOf, which throws on a null element: a null here
    // is a malformed request, and validation should get to say so with a 400
    // rather than the constructor failing first.
    namespaces = namespaces == null ? List.of() : unmodifiable(namespaces);
    clusters = clusters == null ? List.of() : unmodifiable(clusters);
    pods = pods == null ? List.of() : unmodifiable(pods);
    format = format == null ? OutputFormat.JSON : format;
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

  /** A request with no pods picked by name, as every request was before they could be. */
  public ExportRequest(
      List<String> namespaces,
      String podPattern,
      String containerPattern,
      String lineFilter,
      Instant from,
      Instant to,
      List<String> clusters) {
    this(namespaces, podPattern, containerPattern, lineFilter, from, to, clusters, List.of());
  }

  /** A request in the default format, as every request was before there was a choice. */
  public ExportRequest(
      List<String> namespaces,
      String podPattern,
      String containerPattern,
      String lineFilter,
      Instant from,
      Instant to,
      List<String> clusters,
      List<String> pods) {
    this(namespaces, podPattern, containerPattern, lineFilter, from, to, clusters, pods, null);
  }

  /** The same request, restricted to {@code clusters}. */
  public ExportRequest withClusters(List<String> clusters) {
    return new ExportRequest(
        namespaces, podPattern, containerPattern, lineFilter, from, to, clusters, pods, format);
  }

  /** The range's duration. */
  public java.time.Duration duration() {
    return java.time.Duration.between(from, to);
  }

  /** Whether pods were picked by name and matched by pattern at once. */
  public boolean hasPodsAndPattern() {
    return !pods.isEmpty() && podPattern != null && !podPattern.isBlank();
  }

  /** Whether the range is the right way round and non-empty. */
  public boolean hasValidRange() {
    return to.isAfter(from);
  }
}
