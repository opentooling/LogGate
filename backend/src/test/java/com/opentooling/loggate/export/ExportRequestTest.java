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
}
