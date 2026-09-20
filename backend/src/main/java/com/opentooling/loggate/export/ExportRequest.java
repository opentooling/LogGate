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
 * @param namespaces namespaces to export from; every one is authorized
 * @param podPattern optional glob over pod names, e.g. {@code api-*}
 * @param containerPattern optional glob over container names
 * @param lineFilter optional literal substring the line must contain
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 */
public record ExportRequest(
    @NotEmpty @Size(max = 50) List<@NotEmpty String> namespaces,
    String podPattern,
    String containerPattern,
    String lineFilter,
    @NotNull Instant from,
    @NotNull Instant to) {

  /** The range's duration. */
  public java.time.Duration duration() {
    return java.time.Duration.between(from, to);
  }

  /** Whether the range is the right way round and non-empty. */
  public boolean hasValidRange() {
    return to.isAfter(from);
  }
}
