package com.opentooling.loggate.config;

import com.opentooling.loggate.activity.ActivityRepository;
import com.opentooling.loggate.audit.AuditLog;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.security.AdminPolicy;
import com.opentooling.loggate.web.AuditController;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.pods.PodSource;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.web.ActivityController;
import com.opentooling.loggate.web.ExportController;
import com.opentooling.loggate.web.NamespaceController;
import com.opentooling.loggate.web.PodController;
import com.opentooling.loggate.web.QuotaController;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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
      NamespaceAccess access,
      AuthorizationGate authorization,
      PodSource pods,
      @Value("${loggate.pods.allow-pattern:true}") boolean podPatternAllowed,
      AdminPolicy admins) {
    return new NamespaceController(
        access, authorization, pods.enabled(), podPatternAllowed, admins);
  }

  /**
   * The public pages at short paths. Each is a static file built with the UI;
   * a forward keeps its address as typed.
   */
  @Bean
  org.springframework.web.servlet.config.annotation.WebMvcConfigurer publicPages() {
    return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
      @Override
      public void addViewControllers(
          org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        registry.addViewController("/welcome").setViewName("forward:/welcome.html");
        registry.addViewController("/guide").setViewName("forward:/guide/index.html");
        registry.addViewController("/guide/").setViewName("forward:/guide/index.html");
      }
    };
  }

  /** Answers every controller's refusals the same way; see ApiErrors. */
  @Bean
  com.opentooling.loggate.web.ApiErrors apiErrors() {
    return new com.opentooling.loggate.web.ApiErrors();
  }

  @Bean
  AdminPolicy adminPolicy(@Value("${loggate.access.admin-role:loggate-admin}") String role) {
    return new AdminPolicy(role);
  }

  @Bean
  AuditController auditController(AuditLog log, AdminPolicy admins) {
    return new AuditController(log, admins);
  }

  @Bean
  PodController podController(
      PodSource pods,
      NamespaceAccess access,
      AuthorizationGate authorization,
      @Value("${loggate.quotas.max-range:2d}") Duration maxRange) {
    return new PodController(pods, access, authorization, maxRange);
  }

  @Bean
  ActivityController activityController(ActivityRepository activity, AdminPolicy admins) {
    return new ActivityController(activity, admins);
  }

  @Bean
  ExportController exportController(
      AuthorizationGate authorization,
      NamespaceAccess access,
      ExportService exports,
      com.opentooling.loggate.delivery.DeliveryService delivery,
      AuditService audit,
      @Value("${loggate.pods.allow-pattern:true}") boolean podPatternAllowed) {
    return new ExportController(
        authorization, access, exports, delivery, audit, podPatternAllowed);
  }

  @Bean
  QuotaController quotaController(NamespaceAccess access, QuotaGuard quotas) {
    return new QuotaController(access, quotas);
  }
}
