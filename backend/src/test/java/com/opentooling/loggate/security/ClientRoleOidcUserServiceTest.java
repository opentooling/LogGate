package com.opentooling.loggate.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ClientRoleOidcUserServiceTest {

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private static ClientRegistration registration(String userNameAttribute) {
    return ClientRegistration.withRegistrationId("keycloak")
        .clientId("loggate")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
        .authorizationUri("http://auth.test/auth")
        .tokenUri("http://auth.test/token")
        .userNameAttributeName(userNameAttribute)
        .build();
  }

  private static OidcIdToken idToken(Map<String, Object> extraClaims) {
    OidcIdToken.Builder builder =
        OidcIdToken.withTokenValue("id-token")
            .subject("carol-subject")
            .claim("preferred_username", "carol")
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.EPOCH.plusSeconds(300));
    extraClaims.forEach(builder::claim);
    return builder.build();
  }

  /** An access token shaped as Keycloak issues it; the signature is not read. */
  private static String jwt(Map<String, Object> claims) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
        + "."
        + encoder.encodeToString(JSON.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8))
        + ".signature";
  }

  private static OidcUser signIn(String accessToken, Map<String, Object> idClaims, String nameAttribute) {
    OidcIdToken id = idToken(idClaims);
    ClientRoleOidcUserService service =
        new ClientRoleOidcUserService(
            request -> new DefaultOidcUser(Set.of(new SimpleGrantedAuthority("OIDC_USER")), id));
    return service.loadUser(
        new OidcUserRequest(
            registration(nameAttribute),
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessToken, Instant.EPOCH, Instant.EPOCH.plusSeconds(300)),
            id));
  }

  private static Set<String> rolesOf(OidcUser user) {
    return AuthenticatedUser.from(user).clientRoles();
  }

  @Test
  void readsTheClientRolesKeycloakPutsInTheAccessToken() {
    // Roles a group grants appear here for every member, exactly like roles
    // granted directly, so group membership needs no handling of its own.
    String token =
        jwt(
            Map.of(
                "resource_access",
                Map.of(
                    "loggate", Map.of("roles", List.of("export-logs")),
                    "account", Map.of("roles", List.of("manage-account")))));

    OidcUser user = signIn(token, Map.of(), "preferred_username");

    assertThat(rolesOf(user)).containsExactly("export-logs");
    assertThat(user.getName()).isEqualTo("carol");
    // What Spring granted is kept alongside.
    assertThat(user.getAuthorities()).extracting("authority").contains("OIDC_USER");
  }

  @Test
  void ignoresRolesOnOtherClients() {
    // A role of the same name on another client is someone else's decision.
    String token = jwt(Map.of("resource_access", Map.of("grafana", Map.of("roles", List.of("export-logs")))));

    assertThat(rolesOf(signIn(token, Map.of(), "preferred_username"))).isEmpty();
  }

  @Test
  void alsoReadsTheClaimFromTheIdTokenWhenAProviderPutsItThere() {
    Map<String, Object> idClaims =
        Map.of("resource_access", Map.of("loggate", Map.of("roles", List.of("export-logs", ""))));

    assertThat(rolesOf(signIn("opaque-token", idClaims, "preferred_username")))
        .containsExactly("export-logs");
  }

  @Test
  void grantsNoRolesFromAnOpaqueOrGarbledToken() {
    assertThat(rolesOf(signIn("opaque-token", Map.of(), "preferred_username"))).isEmpty();
    assertThat(rolesOf(signIn("a.%%%.c", Map.of(), "preferred_username"))).isEmpty();
    String notJson =
        "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes(StandardCharsets.UTF_8)) + ".s";
    assertThat(rolesOf(signIn(notJson, Map.of(), "preferred_username"))).isEmpty();
  }

  @Test
  void ignoresRoleEntriesThatAreNotNames() {
    String token = jwt(Map.of("resource_access", Map.of("loggate", Map.of("roles", List.of(42, "export-logs")))));

    assertThat(rolesOf(signIn(token, Map.of(), "preferred_username"))).containsExactly("export-logs");
  }

  @Test
  void fallsBackToTheSubjectAsTheNameWhenNoAttributeIsConfigured() {
    assertThat(signIn("opaque-token", Map.of(), "").getName()).isEqualTo("carol-subject");
  }

  @Test
  void treatsAMissingTokenAsCarryingNoRoles() {
    assertThat(ClientRoleOidcUserService.accessTokenClaims(null)).isEmpty();
  }

  @Test
  void grantsNoRolesFromAnEncryptedToken() {
    // Five parts, a JWE: it parses, but nothing in it can be read without the key.
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"RSA-OAEP\",\"enc\":\"A256GCM\"}".getBytes(StandardCharsets.UTF_8));
    String part = encoder.encodeToString("x".getBytes(StandardCharsets.UTF_8));

    assertThat(
            ClientRoleOidcUserService.accessTokenClaims(
                String.join(".", header, part, part, part, part)))
        .isEmpty();
  }
}
