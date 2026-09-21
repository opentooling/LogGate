package com.opentooling.loggate.authz;

import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.namespaces.NamespaceCatalog;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.quota.BudgetHolder;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Teams export the namespaces labelled as theirs.
 *
 * <p>Ownership is read from the Kubernetes API, and that API can only speak for
 * its own cluster. So when Loki holds logs from several clusters, this cluster
 * is the only one offered, and every request is pinned to it: without the pin,
 * a team entitled to {@code platform-dev} here would also receive
 * {@code platform-dev} from every other cluster that happens to have one.
 */
public class TeamLabelAccess implements NamespaceAccess {

  private final NamespaceAuthorizer authorizer;
  private final NamespaceCatalog catalog;
  /** This cluster's name in the cluster label, or empty when there is no cluster label. */
  private final String localCluster;

  public TeamLabelAccess(NamespaceAuthorizer authorizer, NamespaceCatalog catalog, String localCluster) {
    this.authorizer = authorizer;
    this.catalog = catalog;
    this.localCluster = localCluster == null ? "" : localCluster;
  }

  @Override
  public com.opentooling.loggate.config.LogGateProperties.AccessMode mode() {
    return com.opentooling.loggate.config.LogGateProperties.AccessMode.TEAM_LABEL;
  }

  @Override
  public boolean requiresNamespaces() {
    return true;
  }

  @Override
  public AccessDecision authorize(
      AuthenticatedUser user, List<String> clusters, List<String> namespaces) {
    AccessDecision byNamespace = authorizer.authorize(user.groups(), namespaces);
    Map<String, DenialReason> denied = new LinkedHashMap<>(byNamespace.denied());
    for (String cluster : clusters) {
      if (!cluster.equals(localCluster)) {
        denied.put(AccessDecision.clusterKey(cluster), DenialReason.UNKNOWN_CLUSTER);
      }
    }
    return new AccessDecision(byNamespace.allowed(), denied);
  }

  @Override
  public ExportRequest scope(ExportRequest request) {
    return localCluster.isEmpty() ? request : request.withClusters(List.of(localCluster));
  }

  @Override
  public List<String> clusters(AuthenticatedUser user) {
    return localCluster.isEmpty() ? List.of() : List.of(localCluster);
  }

  @Override
  public List<NamespaceInfo> namespaces(AuthenticatedUser user, List<String> clusters) {
    return authorizer.visibleTo(user.groups());
  }

  /**
   * The teams owning the namespaces, resolved now and stored on the job. The
   * budget is an accounting question about the past, and re-deriving it later
   * would let a relabelled namespace quietly rewrite who spent what.
   */
  @Override
  public List<BudgetHolder> chargedTo(AuthenticatedUser user, ExportRequest request) {
    return request.namespaces().stream()
        .map(catalog::find)
        .flatMap(Optional::stream)
        .map(NamespaceInfo::team)
        .distinct()
        .map(BudgetHolder::team)
        .toList();
  }

  @Override
  public List<BudgetHolder> budgets(AuthenticatedUser user) {
    return authorizer.visibleTo(user.groups()).stream()
        .map(NamespaceInfo::team)
        .distinct()
        .map(BudgetHolder::team)
        .toList();
  }

  @Override
  public String barrier(AuthenticatedUser user) {
    return authorizer.visibleTo(user.groups()).isEmpty()
        ? "You are not in any group that owns a labelled namespace, so there is nothing you can"
            + " export. Ask the team that owns the namespace to add you to its group."
        : null;
  }
}
