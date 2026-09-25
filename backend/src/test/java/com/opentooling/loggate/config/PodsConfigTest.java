package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.pods.NoPodSource;
import com.opentooling.loggate.pods.PrometheusPodSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PodsConfigTest {

  private static final Path TEST_CA = Path.of("src/test/resources/tls/test-ca.pem");

  private static LogGateProperties.Pods pods(String url, String ca) {
    return new LogGateProperties.Pods(
        url, "kube_pod_info", "pod", "namespace", "", "", "", ca,
        Duration.ofSeconds(5), 100, Duration.ofSeconds(60), true);
  }

  @Test
  void listsNoPodsUntilAnEndpointIsConfigured() {
    assertThat(
            new PodsConfig().podSource(TestProperties.defaults(), JsonMapper.builder().build()))
        .isInstanceOf(NoPodSource.class);
  }

  @Test
  void listsFromTheConfiguredEndpointTrustingItsCa() {
    assertThat(
            PodsConfig.podSource(
                TestProperties.withPods(pods("https://thanos.test:9091", TEST_CA.toString())),
                JsonMapper.builder().build(),
                Clock.systemUTC()))
        .isInstanceOf(PrometheusPodSource.class);
    assertThat(
            PodsConfig.podSource(
                TestProperties.withPods(pods("http://prometheus.test", "")),
                JsonMapper.builder().build(),
                Clock.systemUTC()))
        .isInstanceOf(PrometheusPodSource.class);
  }

  @Test
  void refusesACaThatCannotBeRead() {
    assertThatThrownBy(() -> PodsConfig.sslContext(Path.of("does/not/exist.pem")))
        .isInstanceOf(RuntimeException.class);
  }
}
