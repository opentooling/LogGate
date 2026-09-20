package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WindowPlannerTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final long MB = 1024L * 1024L;

  private static WindowPlanner planner(long targetBytes, Duration min, Duration max, int maxCount) {
    return new WindowPlanner(
        TestProperties.of(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"),
            new LogGateProperties.Loki("http://loki.test", "", 5000, Duration.ofSeconds(30)),
            new LogGateProperties.Windows(targetBytes, min, max, maxCount)));
  }

  private static WindowPlanner defaultPlanner() {
    return planner(256 * MB, Duration.ofMinutes(1), Duration.ofHours(1), 5000);
  }

  @Test
  void sizesWindowsFromTheVolumeEstimate() {
    // 24 GB over 24 hours is 1 GB/hour, so a 256 MB target is about 15 minutes.
    WindowPlan plan =
        defaultPlanner().plan(FROM, FROM.plus(Duration.ofHours(24)), 24L * 1024 * MB);

    assertThat(plan.windowDuration()).isEqualTo(Duration.ofMinutes(15));
    assertThat(plan.windowCount()).isEqualTo(96);
  }

  @Test
  void coversTheWholeRangeWithoutGapsOrOverlaps() {
    WindowPlan plan = defaultPlanner().plan(FROM, FROM.plus(Duration.ofHours(2)), 2048 * MB);

    assertThat(plan.windows().getFirst().from()).isEqualTo(FROM);
    assertThat(plan.windows().getLast().to()).isEqualTo(FROM.plus(Duration.ofHours(2)));
    for (int i = 1; i < plan.windowCount(); i++) {
      // Each window starts exactly where the previous one ended: end is
      // exclusive, so nothing is lost and nothing is fetched twice.
      assertThat(plan.windows().get(i).from()).isEqualTo(plan.windows().get(i - 1).to());
      assertThat(plan.windows().get(i).index()).isEqualTo(i);
    }
  }

  @Test
  void truncatesTheLastWindowAtTheEndOfTheRange() {
    WindowPlan plan = defaultPlanner().plan(FROM, FROM.plus(Duration.ofMinutes(70)), 0);

    assertThat(plan.windows().getLast().to()).isEqualTo(FROM.plus(Duration.ofMinutes(70)));
  }

  @Test
  void clampsToTheMinimumForAVeryHighVolume() {
    // A terabyte in an hour would otherwise ask for sub-second windows.
    WindowPlan plan =
        defaultPlanner().plan(FROM, FROM.plus(Duration.ofHours(1)), 1024L * 1024 * MB);

    assertThat(plan.windowDuration()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void clampsToTheMaximumForAVeryLowVolume() {
    WindowPlan plan = defaultPlanner().plan(FROM, FROM.plus(Duration.ofHours(12)), 1024);

    assertThat(plan.windowDuration()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void usesOneWindowWhenTheRangeIsShorterThanTheWindow() {
    WindowPlan plan = defaultPlanner().plan(FROM, FROM.plus(Duration.ofSeconds(30)), 0);

    assertThat(plan.windowCount()).isEqualTo(1);
    assertThat(plan.windows().getFirst().to()).isEqualTo(FROM.plus(Duration.ofSeconds(30)));
  }

  @Test
  void treatsAnUnknownVolumeAsOneLargeWindow() {
    // Nothing to size against: better one big window than thousands of empty
    // small ones.
    WindowPlan plan = defaultPlanner().plan(FROM, FROM.plus(Duration.ofHours(6)), 0);

    assertThat(plan.windowDuration()).isEqualTo(Duration.ofHours(1));
    assertThat(plan.estimatedBytes()).isZero();
  }

  @Test
  void refusesARangeThatDoesNotMoveForward() {
    assertThatThrownBy(() -> defaultPlanner().plan(FROM, FROM, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must end after it starts");
  }

  @Test
  void refusesToPlanMoreWindowsThanTheLimit() {
    // Better an immediate, explainable refusal than a plan nothing finishes.
    WindowPlanner tight = planner(256 * MB, Duration.ofMinutes(1), Duration.ofHours(1), 10);

    assertThatThrownBy(() -> tight.plan(FROM, FROM.plus(Duration.ofHours(24)), 24L * 1024 * MB))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than the limit of 10");
  }
}
