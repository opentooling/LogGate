package com.opentooling.loggate.authz;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.Collection;
import java.util.Map;

/**
 * Authorizes namespaces and records every refusal.
 *
 * <p>One place, so that auditing cannot be forgotten at a new call site: a
 * refusal that is not recorded is the one an investigation will want.
 */
public class AuthorizationGate {

  private final NamespaceAuthorizer authorizer;
  private final AuditService audit;

  public AuthorizationGate(NamespaceAuthorizer authorizer, AuditService audit) {
    this.authorizer = authorizer;
    this.audit = audit;
  }

  /** Authorizes {@code namespaces}, auditing anything refused. */
  public AccessDecision check(
      AuthenticatedUser user, Collection<String> namespaces, String sourceIp) {
    AccessDecision decision = authorizer.authorize(user.groups(), namespaces);
    if (!decision.isFullyAllowed()) {
      audit.record(
          user.subject(),
          AuditAction.NAMESPACE_ACCESS_DENIED,
          Map.of("requested", namespaces, "denied", decision.denied(), "groups", user.groups()),
          sourceIp);
    }
    return decision;
  }
}
