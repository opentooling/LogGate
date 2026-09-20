package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JobsConfigTest {

  private static LogGateProperties.Storage storage(String publicEndpoint, String accessKey) {
    return new LogGateProperties.Storage(
        "http://minio.internal:9000",
        "us-east-1",
        "loggate-exports",
        accessKey,
        "secret",
        true,
        publicEndpoint,
        Duration.ofMinutes(30));
  }

  @Test
  void signsAgainstThePublicEndpointWhenOneIsConfigured() {
    // A presigned URL is opened by a browser, which usually cannot reach the
    // in-cluster endpoint the application uses.
    try (var presigner =
        new JobsConfig().s3Presigner(TestProperties.withStorage(storage("https://minio.example.com", "key")))) {
      assertThat(presigner).isNotNull();
    }
  }

  @Test
  void fallsBackToTheInternalEndpointWhenNoPublicOneIsSet() {
    try (var presigner =
        new JobsConfig().s3Presigner(TestProperties.withStorage(storage("", "key")))) {
      assertThat(presigner).isNotNull();
    }
  }

  @Test
  void usesTheDefaultCredentialChainWhenNoKeysAreConfigured() {
    // So a production deployment can rely on an IAM role instead of a secret.
    try (var presigner = new JobsConfig().s3Presigner(TestProperties.withStorage(storage("", "")))) {
      assertThat(presigner).isNotNull();
    }
  }
}
