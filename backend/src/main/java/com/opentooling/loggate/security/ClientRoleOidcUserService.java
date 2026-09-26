package com.opentooling.loggate.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.util.Collection;
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
 * ID token. It is read with Nimbus, which Spring Security already uses for the
 * ID token. A token that is not a readable JWT simply carries no roles.
 *
 * <p>The same claim in the ID token is honoured too, for providers configured
 * to put it there.
 */
public class ClientRoleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

  public ClientRoleOidcUserService() {
    this(new OidcUserService());
  }

  ClientRoleOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
    this.delegate = delegate;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser user = delegate.loadUser(request);
    String clientId = request.getClientRegistration().getClientId();

    Set<String> roles = new LinkedHashSet<>();
    roles.addAll(rolesIn(accessTokenClaims(request.getAccessToken().getTokenValue()), clientId));
    roles.addAll(rolesIn(user.getIdToken().getClaims(), clientId));

    Set<GrantedAuthority> authorities = new LinkedHashSet<>(user.getAuthorities());
    roles.forEach(
        role -> authorities.add(new SimpleGrantedAuthority(AuthenticatedUser.CLIENT_ROLE_AUTHORITY + role)));

    String nameAttribute =
        request.getClientRegistration().getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
    return nameAttribute == null || nameAttribute.isBlank()
        ? new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo())
        : new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameAttribute);
  }

  /** The claims of a JWT access token, or none when it is not a readable one. */
  static Map<String, Object> accessTokenClaims(String token) {
    if (token == null) {
      return Map.of();
    }
    try {
      // An encrypted token parses, but its claims are unreadable without the key.
      JWTClaimsSet claims = JWTParser.parse(token).getJWTClaimsSet();
      return claims == null ? Map.of() : claims.getClaims();
    } catch (ParseException e) {
      // Opaque or malformed: no roles can be read from it, which denies rather
      // than fails the sign-in.
      return Map.of();
    }
  }

  private static Set<String> rolesIn(Map<String, Object> claims, String clientId) {
    Set<String> roles = new LinkedHashSet<>();
    if (claims.get("resource_access") instanceof Map<?, ?> clients
        && clients.get(clientId) instanceof Map<?, ?> client
        && client.get("roles") instanceof Collection<?> names) {
      for (Object name : names) {
        if (name instanceof String role && !role.isBlank()) {
          roles.add(role);
        }
      }
    }
    return roles;
  }
}
