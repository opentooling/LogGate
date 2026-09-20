package com.opentooling.loggate.config;

import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.namespaces.NamespaceCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans that decide who may export which namespaces. */
@Configuration(proxyBeanMethods = false)
public class AuthorizationConfig {

  @Bean
  GroupNameRenderer groupNameRenderer(LogGateProperties properties) {
    return new GroupNameRenderer(properties);
  }

  @Bean
  NamespaceAuthorizer namespaceAuthorizer(NamespaceCatalog catalog) {
    return new NamespaceAuthorizer(catalog);
  }

  @Bean
  AuthorizationGate authorizationGate(NamespaceAuthorizer authorizer, AuditService audit) {
    return new AuthorizationGate(authorizer, audit);
  }
}
