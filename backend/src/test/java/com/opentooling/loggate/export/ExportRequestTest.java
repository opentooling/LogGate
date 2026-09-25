package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportRequestTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");

  private static ExportRequest between(Instant from, Instant to) {
    return new ExportRequest(List.of("platform-dev"), null, null, null, from, to);
  }

  @Test
  void reportsItsDuration() {
    assertThat(between(FROM, FROM.plus(Duration.ofHours(48))).duration())
        .isEqualTo(Duration.ofHours(48));
  }

  @Test
  void acceptsARangeThatMovesForward() {
    assertThat(between(FROM, FROM.plusSeconds(1)).hasValidRange()).isTrue();
  }

  @Test
  void rejectsAnEmptyOrReversedRange() {
    assertThat(between(FROM, FROM).hasValidRange()).isFalse();
    assertThat(between(FROM, FROM.minusSeconds(1)).hasValidRange()).isFalse();
  }

  @Test
  void knowsWhenPodsArePickedAndMatchedAtOnce() {
    Instant from = Instant.parse("2026-09-20T00:00:00Z");
    Instant to = from.plusSeconds(60);
    assertThat(new ExportRequest(List.of("a"), "api-*", null, null, from, to, List.of(), List.of("api-1"))
            .hasPodsAndPattern())
        .isTrue();
    assertThat(new ExportRequest(List.of("a"), " ", null, null, from, to, List.of(), List.of("api-1"))
            .hasPodsAndPattern())
        .isFalse();
    assertThat(new ExportRequest(List.of("a"), null, null, null, from, to, List.of(), List.of("api-1"))
            .hasPodsAndPattern())
        .isFalse();
    ExportRequest noPods = new ExportRequest(List.of("a"), "api-*", null, null, from, to, List.of(), null);
    assertThat(noPods.pods()).isEmpty();
    assertThat(noPods.hasPodsAndPattern()).isFalse();
    // Narrowing to clusters keeps the pods.
    assertThat(new ExportRequest(List.of("a"), null, null, null, from, to, List.of(), List.of("p"))
            .withClusters(List.of("c")).pods())
        .containsExactly("p");
  }
}
