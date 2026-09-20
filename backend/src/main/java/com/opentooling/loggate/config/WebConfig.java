package com.opentooling.loggate.config;

import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.web.NamespaceController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP endpoints. Controllers keep {@code @RestController} because that is what
 * request mapping is derived from, but they are registered here like every
 * other bean rather than discovered by scanning.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

  @Bean
  NamespaceController namespaceController(NamespaceAuthorizer authorizer, AuditService audit) {
    return new NamespaceController(authorizer, audit);
  }
}
