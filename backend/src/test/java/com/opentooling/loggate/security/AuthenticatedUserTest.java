package com.opentooling.loggate.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class AuthenticatedUserTest {

  private static OidcUser user(Map<String, Object> claims) {
    Map<String, Object> all = new java.util.HashMap<>(claims);
    all.putIfAbsent("sub", "alice-subject");
    OidcIdToken token =
        new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(300), all);
    return new DefaultOidcUser(List.of(), token);
  }

  @Test
  void readsSubjectNameAndGroups() {
    AuthenticatedUser result =
        AuthenticatedUser.from(
            user(Map.of("preferred_username", "alice", "groups", List.of("/ad-platform-dev"))));

    assertThat(result.subject()).isEqualTo("alice-subject");
    assertThat(result.name()).isEqualTo("alice");
    assertThat(result.groups()).containsExactly("/ad-platform-dev");
  }

  @Test
  void fallsBackToTheSubjectWhenThereIsNoUsername() {
    assertThat(AuthenticatedUser.from(user(Map.of())).name()).isEqualTo("alice-subject");
  }

  @Test
  void treatsAMissingGroupsClaimAsNoGroups() {
    // An identity provider that stops emitting groups must look like a caller
    // with no entitlements, not one with every entitlement.
    assertThat(AuthenticatedUser.from(user(Map.of("preferred_username", "alice"))).groups())
        .isEmpty();
  }

  @Test
  void treatsAMalformedGroupsClaimAsNoGroups() {
    assertThat(AuthenticatedUser.from(user(Map.of("groups", "ad-platform-dev"))).groups()).isEmpty();
  }

  @Test
  void ignoresNonStringEntriesInTheGroupsClaim() {
    assertThat(AuthenticatedUser.from(user(Map.of("groups", List.of("ad-platform-dev", 42)))).groups())
        .containsExactly("ad-platform-dev");
  }
}
