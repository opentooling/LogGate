package com.opentooling.loggate.authz;

import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.quota.BudgetHolder;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.List;

/**
 * How callers are granted logs: which clusters and namespaces they may choose
 * from, whether a request is allowed, and who it is charged to.
 *
 * <p>One interface so that every caller asks the same question the same way,
 * whichever mode is configured. There are two answers: team ownership read
 * from Kubernetes labels, and open access to everything in Loki for holders of
 * a role.
 */
public interface NamespaceAccess {

  /** Which mode this is. */
  com.opentooling.loggate.config.LogGateProperties.AccessMode mode();

  /** Whether a request must name its namespaces. When not, none means all. */
  boolean requiresNamespaces();

  /** Authorizes {@code clusters} and {@code namespaces} for {@code user}. */
  AccessDecision authorize(AuthenticatedUser user, List<String> clusters, List<String> namespaces);

  /**
   * The request as it will actually run. Team-label mode pins every request to
   * its own cluster, whatever was asked for, because that is the only cluster
   * whose ownership it can vouch for.
   */
  ExportRequest scope(ExportRequest request);

  /** Clusters {@code user} may choose from; empty when logs are not told apart by cluster. */
  List<String> clusters(AuthenticatedUser user);

  /** Namespaces {@code user} may choose from, within {@code clusters} when any are given. */
  List<NamespaceInfo> namespaces(AuthenticatedUser user, List<String> clusters);

  /** Who an export of {@code request} by {@code user} is charged to. */
  List<BudgetHolder> chargedTo(AuthenticatedUser user, ExportRequest request);

  /** Every budget {@code user} spends from, for showing their allowance. */
  List<BudgetHolder> budgets(AuthenticatedUser user);

  /**
   * Why {@code user} can export nothing at all, as a sentence for them, or
   * null when they can export something.
   */
  String barrier(AuthenticatedUser user);
}
