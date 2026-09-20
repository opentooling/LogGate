package com.opentooling.loggate.namespaces;

import java.util.List;
import java.util.Optional;

/**
 * The catalog used when namespace resolution is switched off or unavailable.
 *
 * <p>It reports itself permanently unready, so every authorization attempt is
 * refused. Running without cluster access is a valid deployment state; granting
 * access while blind to the cluster is not.
 */
public class UnavailableNamespaceCatalog implements NamespaceCatalog {

  @Override
  public boolean isReady() {
    return false;
  }

  @Override
  public Optional<NamespaceInfo> find(String namespace) {
    return Optional.empty();
  }

  @Override
  public List<NamespaceInfo> all() {
    return List.of();
  }
}
