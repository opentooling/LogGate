package com.opentooling.loggate.authz;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Authorizes namespaces and records every refusal.
 *
 * <p>One place, so that auditing cannot be forgotten at a new call site: a
 * refusal that is not recorded is the one an investigation will want.
 */
public class AuthorizationGate {

  private final NamespaceAccess access;
  private final AuditService audit;

  public AuthorizationGate(NamespaceAccess access, AuditService audit) {
    this.access = access;
    this.audit = audit;
  }

  /** Authorizes {@code namespaces}, auditing anything refused. */
  public AccessDecision check(
      AuthenticatedUser user, Collection<String> namespaces, String sourceIp) {
    return check(user, List.of(), namespaces, sourceIp);
  }

  /** Authorizes {@code clusters} and {@code namespaces}, auditing anything refused. */
  public AccessDecision check(
      AuthenticatedUser user,
      Collection<String> clusters,
      Collection<String> namespaces,
      String sourceIp) {
    AccessDecision decision =
        access.authorize(user, List.copyOf(clusters), List.copyOf(namespaces));
    if (!decision.isFullyAllowed()) {
      audit.record(
          user.subject(),
          AuditAction.NAMESPACE_ACCESS_DENIED,
          Map.of(
              "requested", namespaces,
              "clusters", clusters,
              "denied", decision.denied(),
              "groups", user.groups(),
              "clientRoles", user.clientRoles()),
          sourceIp);
    }
    return decision;
  }
}
