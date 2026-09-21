package com.opentooling.loggate.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.jobs.JobState;
import com.opentooling.loggate.storage.PartKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManifestBuilderTest {

  private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
  private static final UUID JOB = UUID.randomUUID();

  private final ExportJobRepository jobs = mock(ExportJobRepository.class);
  private final ManifestBuilder builder =
      new ManifestBuilder(jobs, Clock.fixed(NOW, ZoneOffset.UTC));

  private static ExportJob job(Instant to, long estimated, long written) {
    return new ExportJob(
        JOB,
        "alice-subject",
        JobState.FINALIZING,
        null,
        null,
        List.of("platform-dev"),
        "{namespace=\"platform-dev\"}",
        NOW.minus(Duration.ofHours(6)),
        to,
        estimated,
        estimated * 2,
        2,
        2,
        written,
        42,
        false,
        NOW.minus(Duration.ofHours(1)),
        null,
        null);
  }

  private void withWindowsAndParts() {
    when(jobs.windowSummaries(JOB))
        .thenReturn(
            List.of(
                new ExportJobRepository.WindowSummary(
                    0, NOW.minus(Duration.ofHours(6)), NOW.minus(Duration.ofHours(5)), 20, 200),
                new ExportJobRepository.WindowSummary(
                    1, NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(4)), 22, 220)));
    when(jobs.listArtifacts(JOB, "PART"))
        .thenReturn(
            List.of(
                new ExportJobRepository.Artifact(PartKeys.part(JOB, 0), 80, "aaa", 1L),
                new ExportJobRepository.Artifact(PartKeys.part(JOB, 1), 90, "bbb", 2L)));
  }

  @Test
  void describesEveryPartWithItsCountsAndChecksum() {
    withWindowsAndParts();

    Manifest manifest = builder.build(job(NOW.minus(Duration.ofHours(4)), 400, 420));

    assertThat(manifest.parts()).hasSize(2);
    assertThat(manifest.parts().getFirst().sha256()).isEqualTo("aaa");
    assertThat(manifest.parts().getFirst().entries()).isEqualTo(20);
    assertThat(manifest.parts().getLast().compressedBytes()).isEqualTo(90);
    assertThat(manifest.selector()).isEqualTo("{namespace=\"platform-dev\"}");
    assertThat(manifest.createdAt()).isEqualTo(NOW);
  }

  @Test
  void leavesOutAWindowThatProducedNoPart() {
    when(jobs.windowSummaries(JOB))
        .thenReturn(
            List.of(
                new ExportJobRepository.WindowSummary(
                    0, NOW.minus(Duration.ofHours(6)), NOW.minus(Duration.ofHours(5)), 20, 200)));
    when(jobs.listArtifacts(JOB, "PART")).thenReturn(List.of());

    assertThat(builder.build(job(NOW.minus(Duration.ofHours(4)), 400, 420)).parts()).isEmpty();
  }

  @Test
  void warnsWhenTheRangeReachesTooCloseToThePresent() {
    // Loki serves recent entries from ingesters that may not have flushed, so
    // an export ending moments ago can legitimately be incomplete. Saying so is
    // worth more than implying a completeness it cannot guarantee.
    withWindowsAndParts();

    Manifest manifest = builder.build(job(NOW.minus(Duration.ofMinutes(2)), 400, 420));

    assertThat(manifest.caveats()).anyMatch(c -> c.contains("ingesters"));
  }

  @Test
  void doesNotWarnAboutRecencyForAnOlderRange() {
    withWindowsAndParts();

    Manifest manifest = builder.build(job(NOW.minus(Duration.ofHours(4)), 400, 420));

    assertThat(manifest.caveats()).noneMatch(c -> c.contains("ingesters"));
  }

  @Test
  void explainsWhenMoreWasExportedThanEstimated() {
    withWindowsAndParts();

    Manifest manifest = builder.build(job(NOW.minus(Duration.ofHours(4)), 400, 900));

    assertThat(manifest.caveats()).anyMatch(c -> c.contains("still receiving logs"));
  }

  @Test
  void saysNothingWhenThereIsNothingToWarnAbout() {
    withWindowsAndParts();

    assertThat(builder.build(job(NOW.minus(Duration.ofHours(4)), 400, 100)).caveats()).isEmpty();
  }
}
