package com.opentooling.loggate.namespaces;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** In-memory catalog, so authorization can be tested without a cluster. */
public class FakeNamespaceCatalog implements NamespaceCatalog {

  private final List<NamespaceInfo> entries = new ArrayList<>();
  private boolean ready = true;

  public FakeNamespaceCatalog with(String namespace, String team, String owningGroup) {
    entries.add(new NamespaceInfo(namespace, team, owningGroup));
    return this;
  }

  public FakeNamespaceCatalog notReady() {
    ready = false;
    return this;
  }

  @Override
  public boolean isReady() {
    return ready;
  }

  @Override
  public Optional<NamespaceInfo> find(String namespace) {
    return entries.stream().filter(e -> e.name().equals(namespace)).findFirst();
  }

  @Override
  public List<NamespaceInfo> all() {
    return List.copyOf(entries);
  }
}
