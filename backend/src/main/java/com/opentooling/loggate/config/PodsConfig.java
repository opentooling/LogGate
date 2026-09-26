package com.opentooling.loggate.config;

import com.opentooling.loggate.pods.NoPodSource;
import com.opentooling.loggate.pods.PodSource;
import com.opentooling.loggate.pods.PrometheusPodSource;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Where the pods on offer are listed from. */
@Configuration(proxyBeanMethods = false)
public class PodsConfig {

  @Bean
  PodSource podSource(
      LogGateProperties properties, ObjectMapper json, ObjectProvider<SslBundles> bundles) {
    return podSource(
        properties, json, Clock.systemUTC(), Tls.bundle(bundles, properties.pods().sslBundle()));
  }

  /**
   * A source for the configured endpoint, or none. The endpoint gets a client
   * of its own, with its own timeout and trust, so a slow metrics store cannot
   * hold a Loki call's connection or the other way round.
   */
  static PodSource podSource(
      LogGateProperties properties, ObjectMapper json, Clock clock, SslBundle trust) {
    LogGateProperties.Pods pods = properties.pods();
    if (!pods.enabled()) {
      return new NoPodSource();
    }
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            Tls.httpClient(trust).connectTimeout(pods.timeout()).build());
    requestFactory.setReadTimeout(pods.timeout());
    RestClient http =
        RestClient.builder().baseUrl(pods.metricsUrl()).requestFactory(requestFactory).build();
    return new PrometheusPodSource(http, json, pods, properties.loki().clusterLabel(), clock);
  }
}
