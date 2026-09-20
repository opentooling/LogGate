package com.opentooling.loggate;

import com.opentooling.loggate.config.AuditConfig;
import com.opentooling.loggate.config.AuthorizationConfig;
import com.opentooling.loggate.config.ExportConfig;
import com.opentooling.loggate.config.JobsConfig;
import com.opentooling.loggate.config.KubernetesConfig;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * There is deliberately no component scanning. Every bean is declared in a
 * configuration class under {@code config}, so the object graph can be read in
 * one place instead of inferred from annotations spread across the codebase.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(LogGateProperties.class)
@Import({
  SecurityConfig.class,
  KubernetesConfig.class,
  AuthorizationConfig.class,
  ExportConfig.class,
  JobsConfig.class,
  AuditConfig.class,
  WebConfig.class
})
public class LogGateApplication {

  public static void main(String[] args) {
    SpringApplication.run(LogGateApplication.class, args);
  }
}
