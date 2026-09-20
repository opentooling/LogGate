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
