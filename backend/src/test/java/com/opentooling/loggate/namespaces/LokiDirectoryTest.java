package com.opentooling.loggate.namespaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.loki.FakeLokiClient;
import com.opentooling.loggate.loki.LokiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class LokiDirectoryTest {

  private final FakeLokiClient loki = new FakeLokiClient();
  private Instant now = Instant.parse("2026-09-21T12:00:00Z");

  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };

  private LokiDirectory directory(String clusterLabel) {
    return new LokiDirectory(loki, clusterLabel, Duration.ofDays(7), Duration.ofSeconds(60), clock);
  }

  @Test
  void listsClustersFromLokiInOrder() {
    loki.labelValues("cluster", "", "edge-eu", "core-us");

    assertThat(directory("cluster").clusters()).containsExactly("core-us", "edge-eu");
  }

  @Test
  void hasNoClustersWithoutAClusterLabelAndNeverAsks() {
    assertThat(directory("").clusters()).isEmpty();
    assertThat(directory(null).clusters()).isEmpty();
    assertThat(loki.labelQueries()).isEmpty();
  }

  @Test
  void listsNamespacesWithinTheClustersAsked() {
    loki.labelValues("namespace", "{cluster=\"edge-eu\"}", "checkout-prod");
    loki.labelValues("namespace", "", "checkout-prod", "platform-dev");

    LokiDirectory directory = directory("cluster");
    assertThat(directory.namespaces(List.of("edge-eu"))).containsExactly("checkout-prod");
    assertThat(directory.namespaces(List.of())).containsExactly("checkout-prod", "platform-dev");
  }

  @Test
  void asksTheSameQuestionForTheSameClustersInAnyOrder() {
    loki.labelValues("namespace", "{cluster=~\"a|b\"}", "x");
    LokiDirectory directory = directory("cluster");

    directory.namespaces(List.of("b", "a"));
    directory.namespaces(List.of("a", "b", "a"));

    // Sorted and deduplicated first, so both are one cache entry and one query.
    assertThat(loki.labelQueries()).containsExactly("namespace|{cluster=~\"a|b\"}");
  }

  @Test
  void ignoresClustersWhenThereIsNoClusterLabel() {
    loki.labelValues("namespace", "", "platform-dev");

    assertThat(directory("").namespaces(List.of("edge-eu"))).containsExactly("platform-dev");
  }

  @Test
  void reusesAnAnswerUntilItIsStale() {
    loki.labelValues("cluster", "", "edge-eu");
    LokiDirectory directory = directory("cluster");

    directory.clusters();
    now = now.plusSeconds(30);
    directory.clusters();
    assertThat(loki.labelQueries()).hasSize(1);

    now = now.plusSeconds(31);
    directory.clusters();
    assertThat(loki.labelQueries()).hasSize(2);
  }

  @Test
  void fallsBackToTheLastAnswerWhenLokiFails() {
    loki.labelValues("cluster", "", "edge-eu");
    LokiDirectory directory = directory("cluster");
    directory.clusters();

    now = now.plusSeconds(120);
    loki.failNextQuery(new LokiException("down", null));

    assertThat(directory.clusters()).containsExactly("edge-eu");
  }

  @Test
  void failsWhenLokiFailsAndThereIsNothingToFallBackTo() {
    loki.failNextQuery(new LokiException("down", null));

    assertThatThrownBy(() -> directory("cluster").clusters()).isInstanceOf(LokiException.class);
  }

  @Test
  void startsOverRatherThanGrowingWithoutLimit() {
    LokiDirectory directory = directory("cluster");
    for (int i = 0; i < 300; i++) {
      directory.namespaces(List.of("cluster-" + i));
    }
    // Every combination was asked once; none were served from a cache that
    // had grown past its bound.
    assertThat(loki.labelQueries()).hasSize(300);
    directory.namespaces(List.of("cluster-299"));
    assertThat(loki.labelQueries()).hasSize(300);
  }
}
