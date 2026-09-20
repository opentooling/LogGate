package com.opentooling.loggate.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExportJobTest {

  private static ExportJob withWindows(int total, int done) {
    return new ExportJob(
        UUID.randomUUID(),
        "alice-subject",
        JobState.RUNNING,
        null,
        null,
        List.of("platform-dev"),
        "{}",
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(3600),
        0,
        0,
        total,
        done,
        0,
        0,
        false,
        Instant.EPOCH,
        null);
  }

  @Test
  void reportsProgressAsAFraction() {
    assertThat(withWindows(8, 2).progress()).isEqualTo(0.25);
  }

  @Test
  void reportsNoProgressForAJobWithNoWindowsRatherThanDividingByZero() {
    assertThat(withWindows(0, 0).progress()).isZero();
  }
}
