package com.opentooling.loggate;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A single Postgres container, reused across the whole test run: the container
 * is not declared {@code @Container}, so Testcontainers' JVM shutdown hook owns
 * its lifecycle rather than each test class paying for a fresh start.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:17-alpine");
  }
}
