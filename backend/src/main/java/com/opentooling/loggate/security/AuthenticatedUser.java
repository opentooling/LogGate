package com.opentooling.loggate.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The caller, reduced to what authorization actually needs.
 *
 * @param subject stable identifier from the OIDC provider
 * @param name display name, falling back to the subject
 * @param groups group claims, exactly as the provider emitted them
 */
public record AuthenticatedUser(String subject, String name, Set<String> groups) {

  private static final String GROUPS_CLAIM = "groups";

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
    String name = user.getPreferredUsername() != null ? user.getPreferredUsername() : user.getSubject();
    return new AuthenticatedUser(user.getSubject(), name, groups);
  }
}
