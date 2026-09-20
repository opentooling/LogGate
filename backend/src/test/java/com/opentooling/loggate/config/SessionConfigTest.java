package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The decisive test for multi-replica login.
 *
 * <p>Everything the login flow keeps in the session has to survive a round trip
 * through the store. Spring Security 7's authorization request is not
 * Serializable, so a session store using Java serialization drops it and the
 * callback fails with no useful error - which is exactly the bug this
 * configuration exists to prevent.
 */
class SessionConfigTest {

  private final org.springframework.core.convert.support.GenericConversionService conversions =
      new SessionConfig().springSessionConversionService();

  private Object roundTrip(Object value) {
    byte[] stored = conversions.convert(value, byte[].class);
    assertThat(stored).isNotNull();
    return conversions.convert(stored, Object.class);
  }

  @Test
  void survivesTheOAuth2AuthorizationRequest() {
    OAuth2AuthorizationRequest request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("http://auth.localtest.me:8088/realms/loggate/protocol/openid-connect/auth")
            .clientId("loggate")
            .redirectUri("http://loggate.localtest.me:8088/login/oauth2/code/keycloak")
            .scopes(java.util.Set.of("openid", "profile"))
            .state("a-state-value")
            .build();

    Object recovered = roundTrip(request);

    assertThat(recovered).isInstanceOf(OAuth2AuthorizationRequest.class);
    OAuth2AuthorizationRequest actual = (OAuth2AuthorizationRequest) recovered;
    assertThat(actual.getState()).isEqualTo("a-state-value");
    assertThat(actual.getClientId()).isEqualTo("loggate");
    assertThat(actual.getGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
  }

  @Test
  void survivesASecurityContext() {
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            "alice", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    Object recovered = roundTrip(new SecurityContextImpl(authentication));

    assertThat(recovered).isInstanceOf(SecurityContextImpl.class);
    assertThat(((SecurityContextImpl) recovered).getAuthentication().getName()).isEqualTo("alice");
  }

  @Test
  void survivesTheSavedRequestThatDrivesThePostLoginRedirect() {
    // Spring Security stashes the originally requested URL here and redirects
    // to it after login, so losing it would land every user on a blank page.
    var saved =
        new org.springframework.security.web.savedrequest.DefaultSavedRequest.Builder()
            .setMethod("GET")
            .setScheme("http")
            .setServerName("loggate.localtest.me")
            .setServerPort(8088)
            .setRequestURI("/index.html")
            .setRequestURL("http://loggate.localtest.me:8088/index.html")
            .setQueryString("continue")
            .setContextPath("")
            .build();

    Object recovered = roundTrip(saved);

    assertThat(recovered)
        .isInstanceOf(org.springframework.security.web.savedrequest.DefaultSavedRequest.class);
    assertThat(
            ((org.springframework.security.web.savedrequest.DefaultSavedRequest) recovered)
                .getRedirectUrl())
        .contains("/index.html");
  }

  @Test
  void survivesAnOidcUserWhoseClaimsContainAUrl() throws Exception {
    // The regression that broke multi-replica login: OIDC claims carry the
    // issuer as a java.net.URL, which is not on Spring Security's default
    // allowlist. Storing it succeeded and reading it back failed, so the
    // session was written and then unusable.
    // Mutable collections on purpose: claims arrive parsed from JSON, so they
    // are ArrayList and HashMap rather than the immutable factory types.
    var claims = new java.util.HashMap<String, Object>();
    claims.put("sub", "alice-subject");
    claims.put("preferred_username", "alice");
    claims.put("groups", new java.util.ArrayList<>(List.of("ad-platform-dev")));
    claims.put("iss", java.net.URI.create("http://auth.localtest.me:8088/realms/loggate").toURL());
    var token =
        new org.springframework.security.oauth2.core.oidc.OidcIdToken(
            "token", java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(300), claims);
    var user =
        new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(List.of(), token);
    var authentication =
        new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
            user, List.of(), "keycloak");

    Object recovered = roundTrip(new SecurityContextImpl(authentication));

    assertThat(recovered).isInstanceOf(SecurityContextImpl.class);
    var principal =
        (org.springframework.security.oauth2.core.oidc.user.OidcUser)
            ((SecurityContextImpl) recovered).getAuthentication().getPrincipal();
    assertThat(principal.getSubject()).isEqualTo("alice-subject");
    assertThat(principal.getClaimAsStringList("groups")).containsExactly("ad-platform-dev");
  }

  @Test
  void survivesPlainValues() {
    assertThat(roundTrip("a bare string")).isEqualTo("a bare string");
  }

  @Test
  void doesNotSilentlyMangleATypeItDoesNotKnow() {
    // Spring Security's modules cover the types its own filters store, and
    // deserializing arbitrary types out of a session store is a known
    // vulnerability class, so anything else fails loudly rather than coming
    // back as something subtly different. Nothing this application puts in the
    // session falls outside what those modules cover.
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> roundTrip(List.of("a", "b")))
        .isInstanceOf(org.springframework.core.convert.ConversionFailedException.class);
  }
}
