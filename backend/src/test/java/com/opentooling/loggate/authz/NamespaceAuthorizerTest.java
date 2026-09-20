package com.opentooling.loggate.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.namespaces.FakeNamespaceCatalog;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NamespaceAuthorizerTest {

  private static FakeNamespaceCatalog catalog() {
    return new FakeNamespaceCatalog()
        .with("platform-dev", "platform", "ad-platform-dev")
        .with("payments-dev", "payments", "ad-payments-dev");
  }

  @Test
  void allowsANamespaceOwnedByAGroupTheCallerHolds() {
    var decision =
        new NamespaceAuthorizer(catalog())
            .authorize(Set.of("ad-platform-dev"), List.of("platform-dev"));

    assertThat(decision.allowed()).containsExactly("platform-dev");
    assertThat(decision.isFullyAllowed()).isTrue();
  }

  @Test
  void refusesANamespaceOwnedByAnotherTeam() {
    var decision =
        new NamespaceAuthorizer(catalog())
            .authorize(Set.of("ad-platform-dev"), List.of("payments-dev"));

    assertThat(decision.allowed()).isEmpty();
    assertThat(decision.denied()).containsEntry("payments-dev", DenialReason.NOT_A_GROUP_MEMBER);
    assertThat(decision.isFullyAllowed()).isFalse();
  }

  @Test
  void refusesANamespaceItHasNeverSeen() {
    var decision =
        new NamespaceAuthorizer(catalog()).authorize(Set.of("ad-platform-dev"), List.of("kube-system"));

    assertThat(decision.denied()).containsEntry("kube-system", DenialReason.UNKNOWN_NAMESPACE);
  }

  @Test
  void refusesEverythingWhenTheCatalogHasNotSynced() {
    // The critical case: an unsynced catalog looks exactly like a cluster with
    // no labelled namespaces, so it must deny rather than expose.
    var decision =
        new NamespaceAuthorizer(catalog().notReady())
            .authorize(Set.of("ad-platform-dev"), List.of("platform-dev", "payments-dev"));

    assertThat(decision.allowed()).isEmpty();
    assertThat(decision.denied())
        .containsEntry("platform-dev", DenialReason.CATALOG_UNAVAILABLE)
        .containsEntry("payments-dev", DenialReason.CATALOG_UNAVAILABLE);
  }

  @Test
  void refusesACallerWithNoGroupsAtAll() {
    var decision = new NamespaceAuthorizer(catalog()).authorize(Set.of(), List.of("platform-dev"));

    assertThat(decision.denied()).containsEntry("platform-dev", DenialReason.NOT_A_GROUP_MEMBER);
  }

  @Test
  void acceptsKeycloakFullGroupPathsAndIgnoresCase() {
    // Keycloak emits "/ad-platform-dev" unless the mapper is reconfigured, and
    // directory-derived names vary in case. Neither should lose an entitlement.
    var decision =
        new NamespaceAuthorizer(catalog())
            .authorize(Set.of("/AD-Platform-Dev"), List.of("platform-dev"));

    assertThat(decision.allowed()).containsExactly("platform-dev");
  }

  @Test
  void ignoresSurroundingWhitespaceInGroupClaims() {
    var decision =
        new NamespaceAuthorizer(catalog())
            .authorize(Set.of("  ad-platform-dev  "), List.of("platform-dev"));

    assertThat(decision.allowed()).containsExactly("platform-dev");
  }

  @Test
  void partiallyAllowsAMixedRequest() {
    var decision =
        new NamespaceAuthorizer(catalog())
            .authorize(Set.of("ad-platform-dev"), List.of("platform-dev", "payments-dev"));

    assertThat(decision.allowed()).containsExactly("platform-dev");
    assertThat(decision.denied()).containsOnlyKeys("payments-dev");
  }

  @Test
  void listsOnlyTheNamespacesACallerOwns() {
    List<NamespaceInfo> visible =
        new NamespaceAuthorizer(catalog()).visibleTo(Set.of("ad-payments-dev"));

    assertThat(visible).extracting(NamespaceInfo::name).containsExactly("payments-dev");
  }

  @Test
  void listsNothingWhenTheCatalogHasNotSynced() {
    assertThat(new NamespaceAuthorizer(catalog().notReady()).visibleTo(Set.of("ad-platform-dev")))
        .isEmpty();
  }
}
