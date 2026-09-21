package com.opentooling.loggate.authz;

import java.util.Map;
import java.util.Set;

/**
 * The outcome of authorizing a set of namespaces for one caller.
 *
 * @param allowed namespaces the caller may export
 * @param denied namespaces the caller may not, and why. A refused cluster is
 *     keyed {@code cluster/<name>}
 * @param message a sentence for the caller, when the refusal is about them
 *     rather than about any one namespace, such as a missing role
 */
public record AccessDecision(
    Set<String> allowed, Map<String, DenialReason> denied, String message) {

  /** A decision about namespaces alone. */
  public AccessDecision(Set<String> allowed, Map<String, DenialReason> denied) {
    this(allowed, denied, null);
  }

  /** Key under which a refused cluster is reported. */
  public static String clusterKey(String cluster) {
    return "cluster/" + cluster;
  }

  /** Whether every requested namespace was allowed. */
  public boolean isFullyAllowed() {
    return denied.isEmpty();
  }
}
