package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.namespaces.KubernetesNamespaceCatalog;
import com.opentooling.loggate.namespaces.NamespaceCatalog;
import com.opentooling.loggate.namespaces.UnavailableNamespaceCatalog;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KubernetesConfigTest {

  private static LogGateProperties properties() {
    return new LogGateProperties(
        new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"));
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(KubernetesConfig.class))
          .withBean(LogGateProperties.class, KubernetesConfigTest::properties)
          .withBean(GroupNameRenderer.class, () -> new GroupNameRenderer(properties()));

  @Test
  void wiresTheKubernetesCatalogWhenNamespaceResolutionIsEnabled() {
    runner
        .withPropertyValues("loggate.namespaces.enabled=true")
        .withBean(KubernetesClient.class, () -> mock(KubernetesClient.class))
        .run(context -> assertThat(context).hasSingleBean(KubernetesNamespaceCatalog.class));
  }

  @Test
  void fallsBackToAPermanentlyUnreadyCatalogWhenResolutionIsDisabled() {
    // Disabling namespace resolution must not mean "skip authorization"; it
    // means the catalog can never answer, so every export is refused.
    runner
        .withPropertyValues("loggate.namespaces.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(KubernetesNamespaceCatalog.class);
              assertThat(context).hasSingleBean(UnavailableNamespaceCatalog.class);
              assertThat(context.getBean(NamespaceCatalog.class).isReady()).isFalse();
            });
  }

  @Test
  void buildsARealClientWhenNoneIsSupplied() {
    // Builds the client object only; fabric8 does not contact an API server
    // until a request is made, so this stays hermetic.
    try (KubernetesClient client =
        new KubernetesConfig.KubernetesCatalogConfig().kubernetesClient()) {
      assertThat(client).isNotNull();
    }
  }
}
