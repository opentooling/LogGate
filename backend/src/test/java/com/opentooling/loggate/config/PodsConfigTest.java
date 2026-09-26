package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.pods.NoPodSource;
import com.opentooling.loggate.pods.PrometheusPodSource;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PodsConfigTest {

  private static LogGateProperties.Pods pods(String url) {
    return new LogGateProperties.Pods(
        url, "kube_pod_info", "pod", "namespace", "", "", "", "",
        Duration.ofSeconds(5), 100, Duration.ofSeconds(60), true);
  }

  @Test
  void listsNoPodsUntilAnEndpointIsConfigured() {
    assertThat(
            new PodsConfig()
                .podSource(
                    TestProperties.defaults(),
                    JsonMapper.builder().build(),
                    new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(org.springframework.boot.ssl.SslBundles.class)))
        .isInstanceOf(NoPodSource.class);
  }

  @Test
  void listsFromTheConfiguredEndpointTrustingItsCa() {
    assertThat(
            PodsConfig.podSource(
                TestProperties.withPods(pods("https://thanos.test:9091")),
                JsonMapper.builder().build(),
                Clock.systemUTC(),
                com.opentooling.loggate.TestTls.bundle()))
        .isInstanceOf(PrometheusPodSource.class);
    assertThat(
            PodsConfig.podSource(
                TestProperties.withPods(pods("http://prometheus.test")),
                JsonMapper.builder().build(),
                Clock.systemUTC(),
                null))
        .isInstanceOf(PrometheusPodSource.class);
  }
}
