package com.opentooling.loggate.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The caller, reduced to what authorization actually needs.
 *
 * @param subject stable identifier from the OIDC provider
 * @param name display name, falling back to the subject
 * @param groups group claims, exactly as the provider emitted them
 * @param clientRoles roles the caller holds on LogGate's own OIDC client,
 *     whether assigned directly or inherited through a group
 */
public record AuthenticatedUser(
    String subject, String name, Set<String> groups, Set<String> clientRoles) {

  private static final String GROUPS_CLAIM = "groups";

  /**
   * Prefix of the authorities that carry client roles. They are attached at
   * sign-in by {@link ClientRoleOidcUserService}, which is the only point where
   * the access token that holds them is available.
   */
  public static final String CLIENT_ROLE_AUTHORITY = "LOGGATE_CLIENT_ROLE_";

  public AuthenticatedUser {
    groups = Set.copyOf(groups);
    clientRoles = Set.copyOf(clientRoles);
  }

  /** A caller with no client roles. */
  public AuthenticatedUser(String subject, String name, Set<String> groups) {
    this(subject, name, groups, Set.of());
  }

  /**
   * Reads the caller out of an OIDC token. A missing or malformed groups claim
   * yields an empty set, which denies everything rather than failing the
   * request: an identity provider that stops emitting groups must not look like
   * an identity provider that granted every group.
   */
  public static AuthenticatedUser from(OidcUser user) {
    Object claim = user.getClaims().get(GROUPS_CLAIM);
    Set<String> groups =
        claim instanceof List<?> list
            ? list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toUnmodifiableSet())
            : Set.of();
    Set<String> roles =
        user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(authority -> authority != null && authority.startsWith(CLIENT_ROLE_AUTHORITY))
            .map(authority -> authority.substring(CLIENT_ROLE_AUTHORITY.length()))
            .collect(Collectors.toUnmodifiableSet());
    String name = user.getPreferredUsername() != null ? user.getPreferredUsername() : user.getSubject();
    return new AuthenticatedUser(user.getSubject(), name, groups, roles);
  }
}
