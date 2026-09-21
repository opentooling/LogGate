package com.opentooling.loggate.security;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Signs a user in as Spring would, and records the roles they hold on
 * LogGate's own client.
 *
 * <p>Keycloak puts client roles in the access token under
 * {@code resource_access.<client-id>.roles}, and only there unless its mapper
 * is changed. These are the caller's effective roles: a role granted to a group
 * appears for every member. The access token is only in hand at this moment,
 * during sign-in, so the roles are copied onto the signed-in user as
 * authorities, which the session keeps.
 *
 * <p>The access token is read without checking its signature. It came from the
 * provider's token endpoint over LogGate's own connection, in the same response
 * as the ID token that was verified, so it is exactly as trustworthy as that
 * ID token. A token that is not a JWT simply carries no roles.
 *
 * <p>The same claim in the ID token is honoured too, for providers configured
 * to put it there.
 */
public class ClientRoleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
  private final ObjectMapper json;

  public ClientRoleOidcUserService(ObjectMapper json) {
    this(new OidcUserService(), json);
  }

  ClientRoleOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, ObjectMapper json) {
    this.delegate = delegate;
    this.json = json;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser user = delegate.loadUser(request);
    String clientId = request.getClientRegistration().getClientId();

    Set<String> roles = new LinkedHashSet<>();
    roles.addAll(rolesIn(accessTokenClaims(request.getAccessToken().getTokenValue()), clientId));
    roles.addAll(rolesIn(json.valueToTree(user.getIdToken().getClaims()), clientId));

    Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
    roles.forEach(
        role -> authorities.add(new SimpleGrantedAuthority(AuthenticatedUser.CLIENT_ROLE_AUTHORITY + role)));

    String nameAttribute =
        request.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
    return nameAttribute == null || nameAttribute.isBlank()
        ? new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo())
        : new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameAttribute);
  }

  /** The claims of a JWT access token, or nothing when it is not one. */
  JsonNode accessTokenClaims(String token) {
    String[] parts = token == null ? new String[0] : token.split("\\.");
    if (parts.length != 3) {
      return json.valueToTree(Map.of());
    }
    try {
      byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
      return json.readTree(new String(payload, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
      // Opaque or malformed: no roles can be read from it, which denies rather
      // than fails the sign-in.
      return json.valueToTree(Map.of());
    }
  }

  private static Set<String> rolesIn(JsonNode claims, String clientId) {
    Set<String> roles = new LinkedHashSet<>();
    for (JsonNode role : claims.path("resource_access").path(clientId).path("roles")) {
      if (role.isString() && !role.asString().isBlank()) {
        roles.add(role.asString());
      }
    }
    return roles;
  }
}
