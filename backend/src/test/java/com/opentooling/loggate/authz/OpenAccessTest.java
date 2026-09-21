package com.opentooling.loggate.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.loki.FakeLokiClient;
import com.opentooling.loggate.loki.LokiException;
import com.opentooling.loggate.namespaces.LokiDirectory;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.quota.BudgetHolder;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAccessTest {

  private final FakeLokiClient loki =
      new FakeLokiClient()
          .labelValues("cluster", "", "edge-eu", "core-us")
          .labelValues("namespace", "{cluster=\"edge-eu\"}", "checkout-prod");

  private OpenAccess access(boolean hasClusters) {
    return new OpenAccess(
        new LokiDirectory(
            loki, hasClusters ? "cluster" : "", Duration.ofDays(7), Duration.ofMinutes(1), Clock.systemUTC()),
        "export-logs",
        "loggate",
        hasClusters);
  }

  /** Holds the role, here because a group grants it; how is not this class's concern. */
  private static AuthenticatedUser carol() {
    return new AuthenticatedUser("carol-subject", "carol", Set.of("/log-exporters"), Set.of("export-logs"));
  }

  /** Signed in, but without the role. */
  private static AuthenticatedUser dave() {
    return new AuthenticatedUser("dave-subject", "dave", Set.of(), Set.of("something-else"));
  }

  @Test
  void refusesToExistWithoutARole() {
    // Open mode with no role would open every log to every signed-in user,
    // which must never be reachable by leaving a setting out.
    LokiDirectory directory =
        new LokiDirectory(loki, "", Duration.ofDays(7), Duration.ofMinutes(1), Clock.systemUTC());
    assertThatThrownBy(() -> new OpenAccess(directory, "", "loggate", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("open-role");
    assertThatThrownBy(() -> new OpenAccess(directory, null, "loggate", false))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void describesItself() {
    assertThat(access(true).mode()).isEqualTo(LogGateProperties.AccessMode.OPEN);
    assertThat(access(true).requiresNamespaces()).isFalse();
  }

  @Test
  void grantsAnyNamespaceInAnyKnownClusterToAHolderOfTheRole() {
    AccessDecision decision =
        access(true).authorize(carol(), List.of("edge-eu"), List.of("checkout-prod", "payments-dev"));

    assertThat(decision.isFullyAllowed()).isTrue();
    assertThat(decision.allowed()).containsExactly("checkout-prod", "payments-dev");
  }

  @Test
  void grantsEverythingWhenNothingIsNamed() {
    assertThat(access(true).authorize(carol(), List.of(), List.of()).isFullyAllowed()).isTrue();
  }

  @Test
  void refusesEverythingWithoutTheRoleAndSaysWhich() {
    AccessDecision decision = access(true).authorize(dave(), List.of("edge-eu"), List.of("checkout-prod"));

    assertThat(decision.isFullyAllowed()).isFalse();
    assertThat(decision.denied()).isEqualTo(Map.of("checkout-prod", DenialReason.MISSING_ROLE));
    assertThat(decision.message()).contains("\"export-logs\" role").contains("\"loggate\" client");
  }

  @Test
  void refusesAnExportOfEverythingWithoutTheRole() {
    AccessDecision decision = access(true).authorize(dave(), List.of(), List.of());

    assertThat(decision.denied()).isEqualTo(Map.of("*", DenialReason.MISSING_ROLE));
  }

  @Test
  void refusesAClusterLokiHasNeverHeardOf() {
    AccessDecision decision = access(true).authorize(carol(), List.of("edge-eu", "typo"), List.of());

    assertThat(decision.denied())
        .isEqualTo(Map.of(AccessDecision.clusterKey("typo"), DenialReason.UNKNOWN_CLUSTER));
    assertThat(decision.allowed()).isEmpty();
  }

  @Test
  void refusesAnyClusterWhenLogsAreNotToldApartByCluster() {
    AccessDecision decision = access(false).authorize(carol(), List.of("edge-eu"), List.of());

    assertThat(decision.denied())
        .isEqualTo(Map.of(AccessDecision.clusterKey("edge-eu"), DenialReason.UNKNOWN_CLUSTER));
  }

  @Test
  void failsClosedWhenLokiCannotSayWhichClustersExist() {
    FakeLokiClient down = new FakeLokiClient();
    down.failNextQuery(new LokiException("down"));
    OpenAccess access =
        new OpenAccess(
            new LokiDirectory(down, "cluster", Duration.ofDays(7), Duration.ofMinutes(1), Clock.systemUTC()),
            "export-logs",
            "loggate",
            true);

    AccessDecision decision = access.authorize(carol(), List.of("edge-eu"), List.of());

    assertThat(decision.denied())
        .isEqualTo(Map.of(AccessDecision.clusterKey("edge-eu"), DenialReason.CATALOG_UNAVAILABLE));
  }

  @Test
  void offersLokisClustersAndNamespacesOnlyToAHolderOfTheRole() {
    OpenAccess access = access(true);

    assertThat(access.clusters(carol())).containsExactly("core-us", "edge-eu");
    assertThat(access.namespaces(carol(), List.of("edge-eu")))
        .containsExactly(new NamespaceInfo("checkout-prod", null, null));
    assertThat(access.clusters(dave())).isEmpty();
    assertThat(access.namespaces(dave(), List.of("edge-eu"))).isEmpty();
  }

  @Test
  void leavesTheRequestAsAsked() {
    ExportRequest request =
        new ExportRequest(List.of(), null, null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of("edge-eu"));
    assertThat(access(true).scope(request)).isSameAs(request);
  }

  @Test
  void chargesThePersonBecauseThereAreNoTeams() {
    ExportRequest request =
        new ExportRequest(List.of("checkout-prod"), null, null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));

    assertThat(access(true).chargedTo(carol(), request))
        .containsExactly(new BudgetHolder("user:carol-subject", "carol"));
    assertThat(access(true).budgets(carol()))
        .containsExactly(new BudgetHolder("user:carol-subject", "carol"));
  }

  @Test
  void explainsWhatIsMissingOnlyToThoseMissingIt() {
    assertThat(access(true).barrier(carol())).isNull();
    assertThat(access(true).barrier(dave())).contains("add you to a group that holds it");
  }
}
