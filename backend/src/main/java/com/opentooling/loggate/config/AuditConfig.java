package com.opentooling.loggate.config;

import com.opentooling.loggate.audit.AuditService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Beans that write the audit trail. */
@Configuration(proxyBeanMethods = false)
public class AuditConfig {

  @Bean
  AuditService auditService(JdbcClient db, ObjectMapper json) {
    return new AuditService(db, json);
  }
}
