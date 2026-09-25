package com.opentooling.loggate.config;

import com.opentooling.loggate.pods.NoPodSource;
import com.opentooling.loggate.pods.PodSource;
import com.opentooling.loggate.pods.PrometheusPodSource;
import com.opentooling.loggate.security.CaCertificates;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import javax.net.ssl.SSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Where the pods on offer are listed from. */
@Configuration(proxyBeanMethods = false)
public class PodsConfig {

  @Bean
  PodSource podSource(LogGateProperties properties, ObjectMapper json) {
    return podSource(properties, json, Clock.systemUTC());
  }

  /**
   * A source for the configured endpoint, or none. The endpoint gets a client
   * of its own, with its own timeout and trust, so a slow metrics store cannot
   * hold a Loki call's connection or the other way round.
   */
  static PodSource podSource(LogGateProperties properties, ObjectMapper json, Clock clock) {
    LogGateProperties.Pods pods = properties.pods();
    if (!pods.enabled()) {
      return new NoPodSource();
    }
    HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(pods.timeout());
    if (!pods.caCertificate().isBlank()) {
      client.sslContext(sslContext(Path.of(pods.caCertificate())));
    }
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client.build());
    requestFactory.setReadTimeout(pods.timeout());
    RestClient http =
        RestClient.builder().baseUrl(pods.metricsUrl()).requestFactory(requestFactory).build();
    return new PrometheusPodSource(http, json, pods, properties.loki().clusterLabel(), clock);
  }

  static SSLContext sslContext(Path pem) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, CaCertificates.trustManagers(pem), null);
      return context;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not trust the metrics CA certificate " + pem, e);
    }
  }
}
