package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimestampsTest {

  @Test
  void roundTripsNanosecondPrecision() {
    // Precision matters here: paging compares these values exactly.
    Instant instant = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);

    assertThat(Timestamps.fromNanos(Timestamps.toNanos(instant))).isEqualTo(instant);
  }

  @Test
  void convertsTheEpoch() {
    assertThat(Timestamps.toNanos(Instant.EPOCH)).isZero();
    assertThat(Timestamps.fromNanos(0)).isEqualTo(Instant.EPOCH);
  }

  @Test
  void handlesInstantsBeforeTheEpoch() {
    Instant before = Instant.ofEpochSecond(-2, 500_000_000);

    assertThat(Timestamps.fromNanos(Timestamps.toNanos(before))).isEqualTo(before);
  }
}
