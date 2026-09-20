package com.opentooling.loggate.authz;

import com.opentooling.loggate.namespaces.NamespaceCatalog;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which namespaces a caller may export, by intersecting the groups in
 * their token with the group that owns each namespace.
 *
 * <p>Every path that is not an explicit match is a denial. There is no
 * configuration, and no catalog state, in which an unrecognised namespace is
 * readable.
 */
public class NamespaceAuthorizer {

  private final NamespaceCatalog catalog;

  public NamespaceAuthorizer(NamespaceCatalog catalog) {
    this.catalog = catalog;
  }

  /** Authorizes {@code requested} for a caller holding {@code userGroups}. */
  public AccessDecision authorize(Collection<String> userGroups, Collection<String> requested) {
    Set<String> allowed = new LinkedHashSet<>();
    Map<String, DenialReason> denied = new LinkedHashMap<>();

    if (!catalog.isReady()) {
      // Fail closed. A catalog that has not synced cannot distinguish "no such
      // namespace" from "not loaded yet", so it refuses everything.
      for (String namespace : requested) {
        denied.put(namespace, DenialReason.CATALOG_UNAVAILABLE);
      }
      return new AccessDecision(Set.of(), denied);
    }

    Set<String> normalisedGroups = normalise(userGroups);
    for (String namespace : requested) {
      catalog
          .find(namespace)
          .ifPresentOrElse(
              info -> {
                if (normalisedGroups.contains(normalise(info.owningGroup()))) {
                  allowed.add(namespace);
                } else {
                  denied.put(namespace, DenialReason.NOT_A_GROUP_MEMBER);
                }
              },
              () -> denied.put(namespace, DenialReason.UNKNOWN_NAMESPACE));
    }
    return new AccessDecision(allowed, denied);
  }

  /** Every namespace the caller may export, for populating a picker. */
  public List<NamespaceInfo> visibleTo(Collection<String> userGroups) {
    if (!catalog.isReady()) {
      return List.of();
    }
    Set<String> normalisedGroups = normalise(userGroups);
    return catalog.all().stream()
        .filter(info -> normalisedGroups.contains(normalise(info.owningGroup())))
        .collect(Collectors.toList());
  }

  private static Set<String> normalise(Collection<String> groups) {
    return groups.stream().map(NamespaceAuthorizer::normalise).collect(Collectors.toSet());
  }

  /**
   * Keycloak emits group claims as full paths ({@code /ad-platform-dev}) unless
   * the mapper is configured otherwise, and directory-derived group names vary
   * in case. Comparing on a normalised form avoids an entitlement silently
   * failing over a leading slash.
   */
  private static String normalise(String group) {
    String trimmed = group.trim();
    int lastSlash = trimmed.lastIndexOf('/');
    if (lastSlash >= 0) {
      trimmed = trimmed.substring(lastSlash + 1);
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }
}
