package com.opentooling.loggate.config;

import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.web.ExportController;
import com.opentooling.loggate.web.NamespaceController;
import com.opentooling.loggate.web.QuotaController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP endpoints. Controllers keep {@code @RestController} because request
 * mapping is derived from it, but they are registered here like every other
 * bean rather than discovered by scanning.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

  @Bean
  NamespaceController namespaceController(
      NamespaceAuthorizer authorizer, AuthorizationGate authorization) {
    return new NamespaceController(authorizer, authorization);
  }

  @Bean
  ExportController exportController(
      AuthorizationGate authorization,
      ExportService exports,
      com.opentooling.loggate.delivery.DeliveryService delivery) {
    return new ExportController(authorization, exports, delivery);
  }

  @Bean
  QuotaController quotaController(NamespaceAuthorizer authorizer, QuotaGuard quotas) {
    return new QuotaController(authorizer, quotas);
  }
}
