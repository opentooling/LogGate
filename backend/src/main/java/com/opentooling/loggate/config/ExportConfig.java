package com.opentooling.loggate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.opentooling.loggate.export.ExportEstimator;
import com.opentooling.loggate.export.WindowPager;
import com.opentooling.loggate.export.WindowPlanner;
import com.opentooling.loggate.loki.HttpLokiClient;
import com.opentooling.loggate.loki.LokiClient;

import tools.jackson.databind.ObjectMapper;

/** The extraction engine: sizing, planning and paging. */
@Configuration(proxyBeanMethods = false)
public class ExportConfig {

  /**
   * Built directly rather than from the autoconfigured builder, so the web test
   * slices that import this configuration do not need Boot's RestClient
   * autoconfiguration present. The read timeout is explicit: an export worker
   * blocked forever on one query is worse than one that fails and retries.
   */
  @Bean
  RestClient lokiRestClient(LogGateProperties properties) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
    requestFactory.setReadTimeout(properties.loki().timeout());
    return RestClient.builder()
        .baseUrl(properties.loki().url())
        .requestFactory(requestFactory)
        .build();
  }

  @Bean
  LokiClient lokiClient(
      RestClient lokiRestClient,
      ObjectMapper json,
      LogGateProperties properties,
      com.opentooling.loggate.observability.ExportMetrics metrics) {
    return new HttpLokiClient(
        lokiRestClient,
        json,
        properties,
        duration -> Thread.sleep(duration),
        status -> metrics.lokiThrottled());
  }

  @Bean
  WindowPlanner windowPlanner(LogGateProperties properties) {
    return new WindowPlanner(properties);
  }

  @Bean
  WindowPager windowPager(LokiClient lokiClient, LogGateProperties properties) {
    return new WindowPager(lokiClient, properties.loki().queryLimit());
  }

  @Bean
  ExportEstimator exportEstimator(
      LokiClient lokiClient, WindowPlanner windowPlanner, LogGateProperties properties) {
    return new ExportEstimator(lokiClient, windowPlanner, properties.loki().clusterLabel());
  }
}
