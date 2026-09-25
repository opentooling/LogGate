package com.opentooling.loggate.authz;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.loki.LokiException;
import com.opentooling.loggate.namespaces.LokiDirectory;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.quota.BudgetHolder;
import com.opentooling.loggate.security.AuthenticatedUser;

/**
 * Every namespace Loki holds logs for, to anyone holding a role.
 *
 * <p>Nothing is read from the Kubernetes API, so this works where LogGate runs
 * beside a Loki that collects from clusters it cannot reach. What is on offer
 * is whatever Loki has; what grants access is the role, held on LogGate's own
 * OIDC client, directly or through a group. Without it a caller sees, sizes and
 * downloads nothing, including exports they made while they held it.
 */
public class OpenAccess implements NamespaceAccess {

  private final LokiDirectory directory;
  private final String role;
  private final String clientId;
  private final boolean hasClusters;

  public OpenAccess(LokiDirectory directory, String role, String clientId, boolean hasClusters) {
    if (role == null || role.isBlank()) {
      // Open mode with no role would be every log to every signed-in user.
      // That is never a default, so it cannot be configured by omission.
      throw new IllegalStateException(
          "open access mode needs a client role (loggate.access.open-role) that callers must hold");
    }
    this.directory = directory;
    this.role = role;
    this.clientId = clientId;
    this.hasClusters = hasClusters;
  }

  private boolean holdsRole(AuthenticatedUser user) {
    return user.clientRoles().contains(role);
  }

  @Override
  public com.opentooling.loggate.config.LogGateProperties.AccessMode mode() {
    return com.opentooling.loggate.config.LogGateProperties.AccessMode.OPEN;
  }

  @Override
  public boolean requiresNamespaces() {
    return false;
  }

  @Override
  public AccessDecision authorize(
      AuthenticatedUser user, List<String> clusters, List<String> namespaces) {
    if (!holdsRole(user)) {
      Map<String, DenialReason> denied = new LinkedHashMap<>();
      (namespaces.isEmpty() ? List.of("*") : namespaces)
          .forEach(namespace -> denied.put(namespace, DenialReason.MISSING_ROLE));
      return new AccessDecision(Set.of(), denied, barrier(user));
    }

    Map<String, DenialReason> denied = new LinkedHashMap<>();
    if (!clusters.isEmpty()) {
      // Refusing a cluster Loki has never heard of catches a mistake before it
      // becomes an export of nothing.
      List<String> known;
      try {
        known = hasClusters ? directory.clusters() : List.of();
      } catch (LokiException e) {
        clusters.forEach(c -> denied.put(AccessDecision.clusterKey(c), DenialReason.CATALOG_UNAVAILABLE));
        return new AccessDecision(Set.of(), denied);
      }
      for (String cluster : clusters) {
        if (!known.contains(cluster)) {
          denied.put(AccessDecision.clusterKey(cluster), DenialReason.UNKNOWN_CLUSTER);
        }
      }
    }
    Set<String> allowed = denied.isEmpty() ? new LinkedHashSet<>(namespaces) : Set.of();
    return new AccessDecision(allowed, denied);
  }

  @Override
  public ExportRequest scope(ExportRequest request) {
    return request;
  }

  @Override
  public List<String> clusters(AuthenticatedUser user) {
    return holdsRole(user) ? directory.clusters() : List.of();
  }

  @Override
  public List<NamespaceInfo> namespaces(AuthenticatedUser user, List<String> clusters) {
    if (!holdsRole(user)) {
      return List.of();
    }
    return directory.namespaces(clusters).stream()
        .map(name -> new NamespaceInfo(name, null, null))
        .toList();
  }

  @Override
  public List<BudgetHolder> chargedTo(AuthenticatedUser user, ExportRequest request) {
    return budgets(user);
  }

  @Override
  public List<BudgetHolder> budgets(AuthenticatedUser user) {
    return List.of(BudgetHolder.person(user.subject(), user.name()));
  }

  @Override
  public String barrier(AuthenticatedUser user) {
    return holdsRole(user)
        ? null
        : "Exporting logs here needs the \"" + role + "\" role on the \"" + clientId
            + "\" client. Ask whoever manages access to add you to a group that holds it.";
  }
}
