package com.opentooling.loggate.config;

import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.namespaces.KubernetesNamespaceCatalog;
import com.opentooling.loggate.namespaces.NamespaceCatalog;
import com.opentooling.loggate.namespaces.UnavailableNamespaceCatalog;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KubernetesConfig {

  /**
   * Namespace resolution can be switched off, which is what tests and any
   * cluster-less run use. It is not a way to relax authorization: with it off
   * the catalog is permanently unready and every export is refused.
   *
   * <p>Open access never reads the Kubernetes API, so in that mode no client is
   * created at all and the chart grants no permissions for one.
   */
  @Configuration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Conditional(OnTeamLabelAccess.class)
  @ConditionalOnProperty(
      prefix = "loggate.namespaces",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  public static class KubernetesCatalogConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public KubernetesClient kubernetesClient() {
      return new KubernetesClientBuilder().build();
    }

    @Bean(initMethod = "start")
    KubernetesNamespaceCatalog namespaceCatalog(
        KubernetesClient client, GroupNameRenderer groupNames, LogGateProperties properties) {
      return new KubernetesNamespaceCatalog(client, groupNames, properties);
    }
  }

  @Bean
  @ConditionalOnMissingBean(NamespaceCatalog.class)
  NamespaceCatalog unavailableNamespaceCatalog() {
    return new UnavailableNamespaceCatalog();
  }
}
