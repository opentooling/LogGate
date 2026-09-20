package com.opentooling.loggate.namespaces;

import com.opentooling.loggate.authz.GroupNameRenderer;
import io.fabric8.kubernetes.api.model.Namespace;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a Kubernetes namespace into a {@link NamespaceInfo}, or nothing.
 *
 * <p>Separate from the watching so the rule that decides who owns a namespace
 * is a pure function: it is the security-relevant half, and it is testable
 * without a cluster.
 */
public class NamespaceMapper {

  private final GroupNameRenderer groupNames;
  private final String labelKey;

  public NamespaceMapper(GroupNameRenderer groupNames, String labelKey) {
    this.groupNames = groupNames;
    this.labelKey = labelKey;
  }

  /**
   * The namespace's owner, if it has one. A namespace with no metadata, no
   * labels, no team label, or a blank team label has no owner and is therefore
   * not exportable by anyone.
   */
  public Optional<NamespaceInfo> map(Namespace namespace) {
    if (namespace == null || namespace.getMetadata() == null) {
      return Optional.empty();
    }
    Map<String, String> labels = namespace.getMetadata().getLabels();
    if (labels == null) {
      return Optional.empty();
    }
    String team = labels.get(labelKey);
    if (team == null || team.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new NamespaceInfo(namespace.getMetadata().getName(), team, groupNames.render(team)));
  }
}
