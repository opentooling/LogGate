package com.opentooling.loggate.authz;

import java.util.Map;
import java.util.Set;

/**
 * The outcome of authorizing a set of namespaces for one caller.
 *
 * @param allowed namespaces the caller may export
 * @param denied namespaces the caller may not, and why
 */
public record AccessDecision(Set<String> allowed, Map<String, DenialReason> denied) {

  /** Whether every requested namespace was allowed. */
  public boolean isFullyAllowed() {
    return denied.isEmpty();
  }
}
