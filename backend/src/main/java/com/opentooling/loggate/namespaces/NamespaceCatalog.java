package com.opentooling.loggate.namespaces;

import java.util.List;
import java.util.Optional;

/** Read model of the cluster's namespaces and the teams that own them. */
public interface NamespaceCatalog {

  /**
   * Whether the catalog holds a trustworthy view of the cluster. Callers must
   * treat {@code false} as "deny everything" rather than "allow everything":
   * an empty or stale catalog is indistinguishable from a cluster with no
   * labelled namespaces, and only one of those two readings is safe.
   */
  boolean isReady();

  /** The namespace, if it exists and carries a usable team label. */
  Optional<NamespaceInfo> find(String namespace);

  /** Every labelled namespace currently known. */
  List<NamespaceInfo> all();
}
