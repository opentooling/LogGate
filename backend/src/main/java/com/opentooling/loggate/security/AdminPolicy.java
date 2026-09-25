package com.opentooling.loggate.security;

/**
 * Who may see across everyone's exports: the activity dashboard and the audit
 * trail.
 *
 * <p>A client role on LogGate's own OIDC client, held directly or through a
 * group, read exactly as the open-mode export role is. It is separate from
 * that role on purpose: an auditor need not be able to export, and exporting
 * does not make someone an auditor. A blank role makes nobody an
 * administrator, rather than everybody.
 */
public class AdminPolicy {

  private final String role;

  public AdminPolicy(String role) {
    this.role = role == null ? "" : role.trim();
  }

  /** Whether {@code user} holds the administrator role. */
  public boolean isAdmin(AuthenticatedUser user) {
    return !role.isEmpty() && user.clientRoles().contains(role);
  }

  /** The role's name, for telling someone what they lack. */
  public String role() {
    return role;
  }
}
