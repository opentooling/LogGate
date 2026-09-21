package com.opentooling.loggate.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.namespaces.FakeNamespaceCatalog;
import com.opentooling.loggate.quota.BudgetHolder;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeamLabelAccessTest {

  private static final FakeNamespaceCatalog CATALOG =
      new FakeNamespaceCatalog()
          .with("platform-dev", "platform", "ad-platform-dev")
          .with("platform-test", "platform", "ad-platform-dev")
          .with("payments-dev", "payments", "ad-payments-dev");

  private static TeamLabelAccess access(String localCluster) {
    return new TeamLabelAccess(new NamespaceAuthorizer(CATALOG), CATALOG, localCluster);
  }

  private static AuthenticatedUser alice() {
    return new AuthenticatedUser("alice-subject", "alice", Set.of("ad-platform-dev"));
  }

  private static AuthenticatedUser dave() {
    return new AuthenticatedUser("dave-subject", "dave", Set.of());
  }

  private static ExportRequest request(List<String> namespaces, List<String> clusters) {
    return new ExportRequest(
        namespaces, null, null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), clusters);
  }

  @Test
  void describesItself() {
    assertThat(access("").mode()).isEqualTo(LogGateProperties.AccessMode.TEAM_LABEL);
    assertThat(access("").requiresNamespaces()).isTrue();
  }

  @Test
  void grantsATeamItsOwnNamespaceInItsOwnCluster() {
    assertThat(access("core-eu").authorize(alice(), List.of("core-eu"), List.of("platform-dev")).isFullyAllowed())
        .isTrue();
  }

  @Test
  void refusesAnyOtherCluster() {
    // Kubernetes here cannot say who owns platform-dev in another cluster.
    AccessDecision decision =
        access("core-eu").authorize(alice(), List.of("edge-eu"), List.of("platform-dev"));

    assertThat(decision.denied())
        .isEqualTo(Map.of(AccessDecision.clusterKey("edge-eu"), DenialReason.UNKNOWN_CLUSTER));
  }

  @Test
  void refusesEveryClusterWhenThereIsNoClusterDimension() {
    assertThat(access("").authorize(alice(), List.of("core-eu"), List.of("platform-dev")).denied())
        .containsKey(AccessDecision.clusterKey("core-eu"));
  }

  @Test
  void stillRefusesAnotherTeamsNamespace() {
    assertThat(access("core-eu").authorize(alice(), List.of(), List.of("payments-dev")).denied())
        .isEqualTo(Map.of("payments-dev", DenialReason.NOT_A_GROUP_MEMBER));
  }

  @Test
  void pinsEveryRequestToItsOwnClusterWhateverWasAsked() {
    assertThat(access("core-eu").scope(request(List.of("platform-dev"), List.of())).clusters())
        .containsExactly("core-eu");
    assertThat(access("core-eu").scope(request(List.of("platform-dev"), List.of("edge-eu"))).clusters())
        .containsExactly("core-eu");
  }

  @Test
  void leavesARequestAloneWhenThereIsNoClusterDimension() {
    ExportRequest request = request(List.of("platform-dev"), List.of());
    assertThat(access("").scope(request)).isSameAs(request);
    assertThat(access("").clusters(alice())).isEmpty();
  }

  @Test
  void offersOnlyItsOwnClusterAndTheTeamsNamespaces() {
    assertThat(access("core-eu").clusters(alice())).containsExactly("core-eu");
    assertThat(access("core-eu").namespaces(alice(), List.of()))
        .extracting("name")
        .containsExactlyInAnyOrder("platform-dev", "platform-test");
  }

  @Test
  void chargesTheOwningTeamsOnce() {
    assertThat(access("").chargedTo(alice(), request(List.of("platform-dev", "platform-test", "gone"), List.of())))
        .containsExactly(BudgetHolder.team("platform"));
    assertThat(access("").budgets(alice())).containsExactly(BudgetHolder.team("platform"));
  }

  @Test
  void explainsWhatIsMissingOnlyToThoseMissingIt() {
    assertThat(access("").barrier(alice())).isNull();
    assertThat(access("").barrier(dave())).contains("Ask the team that owns the namespace");
  }
}
