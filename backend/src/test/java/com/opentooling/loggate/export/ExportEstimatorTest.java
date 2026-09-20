package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import com.opentooling.loggate.loki.FakeLokiClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportEstimatorTest {

  /** A fake that remembers when it was asked to sample. */
  private static final class RecordingLokiClient extends FakeLokiClient {
    private Instant sampledAt;

    @Override
    public java.util.OptionalLong sampleBytes(
        String query, Instant at, Duration window) {
      sampledAt = at;
      return java.util.OptionalLong.empty();
    }

    Instant sampledAt() {
      return sampledAt;
    }
  }


  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final long MB = 1024L * 1024L;

  private static WindowPlanner planner() {
    return new WindowPlanner(
        TestProperties.of(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"),
            new LogGateProperties.Loki("http://loki.test", "", 5000, Duration.ofSeconds(30)),
            new LogGateProperties.Windows(256 * MB, Duration.ofMinutes(1), Duration.ofHours(1), 5000)));
  }

  private static ExportRequest request(String lineFilter) {
    return new ExportRequest(
        List.of("platform-dev"), "api-*", null, lineFilter, FROM, FROM.plus(Duration.ofHours(24)));
  }

  @Test
  void reportsTotalAndPerNamespaceBytesWithAWindowPlan() {
    var loki = new FakeLokiClient().volume("platform-dev", 24L * 1024 * MB);

    ExportEstimate estimate = new ExportEstimator(loki, planner()).estimate(request(null));

    assertThat(estimate.estimatedBytes()).isEqualTo(24L * 1024 * MB);
    assertThat(estimate.bytesByNamespace()).containsEntry("platform-dev", 24L * 1024 * MB);
    assertThat(estimate.windowSeconds()).isEqualTo(900);
    assertThat(estimate.windowCount()).isEqualTo(96);
    assertThat(estimate.selector()).isEqualTo("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}");
  }

  @Test
  void sizesOnTheStreamSelectorNotTheLineFilter() {
    // A line filter reduces what gets written but not what Loki reads, so
    // quotas must be set against the unfiltered volume.
    var loki = new FakeLokiClient().volume("platform-dev", 1024);

    ExportEstimate estimate = new ExportEstimator(loki, planner()).estimate(request("timeout"));

    assertThat(loki.selectorsSeen()).containsExactly("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}");
    assertThat(estimate.selector()).endsWith("|= \"timeout\"");
  }

  @Test
  void estimatesWhatALineFilterWillActuallyKeep() {
    // The index cannot see inside lines, so the only honest way to say anything
    // about a filter before running is to sample and extrapolate.
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}", 10_000);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"} |= \"timeout\"", 500);

    ExportEstimate estimate = new ExportEstimator(loki, planner()).estimate(request("timeout"));

    // 5% of the sample matched, so about 5% of the range is expected.
    assertThat(estimate.estimatedBytes()).isEqualTo(1000 * MB);
    assertThat(estimate.filteredBytes()).isEqualTo(50 * MB);
  }

  @Test
  void reportsNoFilteredEstimateWhenThereIsNoFilter() {
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);

    assertThat(new ExportEstimator(loki, planner()).estimate(request(null)).filteredBytes())
        .isNull();
  }

  @Test
  void reportsNoFilteredEstimateWhenTheSampleHeldNothing() {
    // An empty sample says nothing about the filter either way, and inventing
    // a number would be worse than admitting that.
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);

    assertThat(new ExportEstimator(loki, planner()).estimate(request("timeout")).filteredBytes())
        .isNull();
  }

  @Test
  void neverClaimsAFilterKeepsMoreThanEverything() {
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}", 100);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"} |= \"timeout\"", 250);

    assertThat(new ExportEstimator(loki, planner()).estimate(request("timeout")).filteredBytes())
        .isEqualTo(1000 * MB);
  }

  @Test
  void aFailedSampleDoesNotFailTheEstimate() {
    // The unfiltered number is the one admission depends on, so losing the
    // sample must not lose the estimate.
    var loki =
        new FakeLokiClient().volume("platform-dev", 1000 * MB).failNextQuery(new RuntimeException("loki is busy"));

    ExportEstimate estimate = new ExportEstimator(loki, planner()).estimate(request("timeout"));

    assertThat(estimate.estimatedBytes()).isEqualTo(1000 * MB);
  }

  @Test
  void doesNotSampleAFilterWhenThereIsNothingToFilter() {
    // A range with no data at all cannot be sampled, and a blank filter is not
    // a filter.
    var empty = new FakeLokiClient();
    assertThat(new ExportEstimator(empty, planner()).estimate(request("timeout")).filteredBytes())
        .isNull();

    var blank = new FakeLokiClient().volume("platform-dev", 1000 * MB);
    assertThat(new ExportEstimator(blank, planner()).estimate(request("   ")).filteredBytes())
        .isNull();
  }

  @Test
  void samplesFromTheMiddleOfTheRange() {
    // The end of a range is the least representative part of it: traffic may
    // have just changed and the newest entries may not have flushed.
    var loki = new RecordingLokiClient();
    loki.volume("platform-dev", 1000 * MB);

    new ExportEstimator(loki, planner()).estimate(request("timeout"));

    // 24-hour range, 5-minute window: the sample sits around the midpoint.
    assertThat(loki.sampledAt()).isBetween(FROM.plus(Duration.ofHours(11)), FROM.plus(Duration.ofHours(13)));
  }

  @Test
  void samplesTheWholeRangeWhenItIsShorterThanTheSampleWindow() {
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}", 400);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"} |= \"timeout\"", 100);
    var shortRequest =
        new ExportRequest(
            List.of("platform-dev"), "api-*", null, "timeout", FROM, FROM.plus(Duration.ofMinutes(2)));

    assertThat(new ExportEstimator(loki, planner()).estimate(shortRequest).filteredBytes())
        .isEqualTo(250 * MB);
  }

  @Test
  void reportsNothingKeptWhenTheFilterMatchedNothingInASampleThatHadData() {
    // Not an unknown: the window definitely held data and the filter matched
    // none of it, which is exactly the answer the user wants.
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}", 400);

    assertThat(new ExportEstimator(loki, planner()).estimate(request("timeout")).filteredBytes())
        .isZero();
  }

  @Test
  void reportsNoFilteredEstimateWhenTheSampleWindowWasEmpty() {
    var loki = new FakeLokiClient().volume("platform-dev", 1000 * MB);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}", 0);
    loki.sample("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"} |= \"timeout\"", 0);

    assertThat(new ExportEstimator(loki, planner()).estimate(request("timeout")).filteredBytes())
        .isNull();
  }

  @Test
  void handlesARangeWithNoDataAtAll() {
    ExportEstimate estimate = new ExportEstimator(new FakeLokiClient(), planner()).estimate(request(null));

    assertThat(estimate.estimatedBytes()).isZero();
    assertThat(estimate.windowCount()).isEqualTo(24);
  }

  @Test
  void carriesTheRequestedRangeThrough() {
    ExportEstimate estimate = new ExportEstimator(new FakeLokiClient(), planner()).estimate(request(null));

    assertThat(estimate.from()).isEqualTo(FROM);
    assertThat(estimate.to()).isEqualTo(FROM.plus(Duration.ofHours(24)));
  }
}
