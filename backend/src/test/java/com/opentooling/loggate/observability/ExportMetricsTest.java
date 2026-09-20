package com.opentooling.loggate.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.PostgresContainerConfig;
import com.opentooling.loggate.jobs.ExportJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** A metric that never emits is worse than no metric, so these assert on the registry. */
@SpringBootTest
@Import(PostgresContainerConfig.class)
class ExportMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ExportMetrics metrics = new ExportMetrics(registry);

  @Autowired private ExportJobRepository jobs;

  @Test
  void recordsSubmissionsWithTheirOutcome() {
    metrics.submission("accepted", "none");
    metrics.submission("refused", "quota");

    assertThat(registry.get("loggate.exports.submitted").tag("outcome", "refused").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("loggate.exports.submitted").tag("reason", "quota").counter().count())
        .isEqualTo(1);
  }

  @Test
  void tagsAFailureWithItsReasonSoItIsActionable() {
    // "Exports are failing" is not actionable; the reason is.
    metrics.finished("FAILED", "BYTE_LIMIT_EXCEEDED");

    assertThat(
            registry
                .get("loggate.exports.finished")
                .tag("failure", "BYTE_LIMIT_EXCEEDED")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void reportsASuccessfulFinishWithNoFailureTag() {
    metrics.finished("READY", null);

    assertThat(registry.get("loggate.exports.finished").tag("failure", "none").counter().count())
        .isEqualTo(1);
  }

  @Test
  void recordsWhatAWindowProduced() {
    metrics.windowCompleted(Duration.ofSeconds(3), 1_000, 250_000);

    assertThat(registry.get("loggate.window.duration").timer().count()).isEqualTo(1);
    assertThat(registry.get("loggate.entries.exported").counter().count()).isEqualTo(1_000);
    assertThat(registry.get("loggate.bytes.exported").counter().count()).isEqualTo(250_000);
  }

  @Test
  void recordsWindowFailuresByReason() {
    metrics.windowFailed("upstream_failed");

    assertThat(registry.get("loggate.windows.failed").tag("reason", "upstream_failed").counter().count())
        .isEqualTo(1);
  }

  @Test
  void timesLokiQueriesAndCountsThrottling() {
    // Back-pressure should be visible as a number, not only as a slow export.
    String result = metrics.timeLokiQuery(() -> "page");
    metrics.lokiThrottled();

    assertThat(result).isEqualTo("page");
    assertThat(registry.get("loggate.loki.query.duration").timer().count()).isEqualTo(1);
    assertThat(registry.get("loggate.loki.throttled").counter().count()).isEqualTo(1);
  }

  @Test
  void exposesQueueDepthSoCapacityProblemsLookDifferentFromStuckOnes() {
    new QueueGauges(registry, jobs);

    assertThat(registry.get("loggate.jobs.active").gauge().value()).isNotNegative();
    assertThat(registry.get("loggate.windows.pending").gauge().value()).isNotNegative();
    assertThat(registry.get("loggate.windows.running").gauge().value()).isNotNegative();
  }
}
