package com.opentooling.loggate.namespaces;

import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.config.LogGateProperties;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Namespace catalog backed by a Kubernetes informer, so the team-to-namespace
 * mapping tracks the cluster rather than a copy of it taken at startup.
 *
 * <p>If the informer cannot start or has not synced, the catalog reports itself
 * unready and every authorization attempt is refused. That is the whole point:
 * the failure mode of "cannot see the cluster" must be "nobody may export",
 * never "the cluster has no labelled namespaces".
 */
public class KubernetesNamespaceCatalog implements NamespaceCatalog {

  private static final Logger log = LoggerFactory.getLogger(KubernetesNamespaceCatalog.class);

  private final KubernetesClient client;
  private final NamespaceMapper mapper;
  private final String labelKey;

  private volatile SharedIndexInformer<Namespace> informer;

  public KubernetesNamespaceCatalog(
      KubernetesClient client, GroupNameRenderer groupNames, LogGateProperties properties) {
    this.client = client;
    this.labelKey = properties.namespaces().labelKey();
    this.mapper = new NamespaceMapper(groupNames, labelKey);
  }

  /**
   * Starts watching namespaces. A failure here is logged and swallowed rather
   * than propagated: the application stays up, reports itself unready for
   * exports, and retries are the operator's call — an export tool that refuses
   * to boot because a watch failed is less useful than one that boots and
   * refuses exports.
   */
  public void start() {
    try {
      SharedIndexInformer<Namespace> started =
          client.namespaces().inform();
      started.start();
      informer = started;
      log.info("Watching namespaces for label {}", labelKey);
    } catch (RuntimeException e) {
      log.error("Could not start the namespace informer; exports will be refused", e);
    }
  }

  @PreDestroy
  public void stop() {
    SharedIndexInformer<Namespace> current = informer;
    if (current != null) {
      current.stop();
    }
  }

  @Override
  public boolean isReady() {
    SharedIndexInformer<Namespace> current = informer;
    return current != null && current.hasSynced();
  }

  @Override
  public Optional<NamespaceInfo> find(String namespace) {
    if (!isReady()) {
      return Optional.empty();
    }
    return informer.getStore().list().stream()
        .flatMap(ns -> mapper.map(ns).stream())
        .filter(info -> info.name().equals(namespace))
        .findFirst();
  }

  @Override
  public List<NamespaceInfo> all() {
    if (!isReady()) {
      return List.of();
    }
    return informer.getStore().list().stream().flatMap(ns -> mapper.map(ns).stream()).toList();
  }

}
