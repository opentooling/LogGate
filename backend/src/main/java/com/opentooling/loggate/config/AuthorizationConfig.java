package com.opentooling.loggate.config;

import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.authz.OpenAccess;
import com.opentooling.loggate.authz.TeamLabelAccess;
import com.opentooling.loggate.loki.LokiClient;
import com.opentooling.loggate.namespaces.LokiDirectory;
import com.opentooling.loggate.namespaces.NamespaceCatalog;
import java.time.Clock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans that decide who may export which namespaces. */
@Configuration(proxyBeanMethods = false)
public class AuthorizationConfig {

  private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  @Bean
  GroupNameRenderer groupNameRenderer(LogGateProperties properties) {
    return new GroupNameRenderer(properties);
  }

  @Bean
  NamespaceAuthorizer namespaceAuthorizer(NamespaceCatalog catalog) {
    return new NamespaceAuthorizer(catalog);
  }

  @Bean
  NamespaceAccess namespaceAccess(
      LogGateProperties properties,
      NamespaceAuthorizer authorizer,
      NamespaceCatalog catalog,
      LokiClient loki,
      @Value("${spring.security.oauth2.client.registration.keycloak.client-id:loggate}")
          String clientId) {
    return namespaceAccess(properties, authorizer, catalog, loki, clientId, Clock.systemUTC());
  }

  /**
   * Builds the configured access mode, refusing any configuration that would
   * grant more than it appears to. Each refusal stops the application starting,
   * because an access rule that is quietly not applied is worse than a pod that
   * will not start and says why.
   */
  static NamespaceAccess namespaceAccess(
      LogGateProperties properties,
      NamespaceAuthorizer authorizer,
      NamespaceCatalog catalog,
      LokiClient loki,
      String clientId,
      Clock clock) {
    LogGateProperties.Loki lokiSettings = properties.loki();
    if (lokiSettings.hasClusters() && !LABEL_NAME.matcher(lokiSettings.clusterLabel()).matches()) {
      throw new IllegalStateException(
          "loggate.loki.cluster-label is not a valid label name: " + lokiSettings.clusterLabel());
    }

    LogGateProperties.Access access = properties.access();
    if (access.teamLabel()) {
      String localCluster = properties.namespaces().cluster();
      if (lokiSettings.hasClusters() && localCluster.isBlank()) {
        throw new IllegalStateException(
            "team-label access with a cluster label needs loggate.namespaces.cluster: the name"
                + " of this cluster, which every export is pinned to");
      }
      return new TeamLabelAccess(authorizer, catalog, lokiSettings.hasClusters() ? localCluster : "");
    }

    LokiDirectory directory =
        new LokiDirectory(
            loki,
            lokiSettings.clusterLabel(),
            access.discoveryWindow(),
            access.discoveryCacheTtl(),
            clock);
    return new OpenAccess(directory, access.openRole(), clientId, lokiSettings.hasClusters());
  }

  @Bean
  AuthorizationGate authorizationGate(NamespaceAccess access, AuditService audit) {
    return new AuthorizationGate(access, audit);
  }
}
