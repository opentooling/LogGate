package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ExportWindowTest {

  @Test
  void exposesItsBoundsAsLokiTimestamps() {
    var window =
        new ExportWindow(
            3, Instant.ofEpochSecond(1_700_000_000L), Instant.ofEpochSecond(1_700_000_900L));

    assertThat(window.fromNanos()).isEqualTo(1_700_000_000_000_000_000L);
    assertThat(window.toNanos()).isEqualTo(1_700_000_900_000_000_000L);
    assertThat(window.index()).isEqualTo(3);
  }
}
