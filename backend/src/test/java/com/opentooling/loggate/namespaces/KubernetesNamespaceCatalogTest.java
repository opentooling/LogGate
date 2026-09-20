package com.opentooling.loggate.namespaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.config.LogGateProperties;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises the catalog against a mock Kubernetes API server. */
@EnableKubernetesMockClient(crud = true)
class KubernetesNamespaceCatalogTest {

  // Instance field, not static: each test gets its own mock API server, so
  // namespaces created by one test cannot leak into another's assertions.
  KubernetesClient client;

  private KubernetesNamespaceCatalog catalog;

  private KubernetesNamespaceCatalog started() {
    LogGateProperties properties =
        new LogGateProperties(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"));
    catalog = new KubernetesNamespaceCatalog(client, new GroupNameRenderer(properties), properties);
    catalog.start();
    await().atMost(Duration.ofSeconds(10)).until(catalog::isReady);
    return catalog;
  }

  private void createNamespace(String name, Map<String, String> labels) {
    client
        .namespaces()
        .resource(
            new NamespaceBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(labels)
                .endMetadata()
                .build())
        .create();
  }

  @AfterEach
  void tearDown() {
    if (catalog != null) {
      catalog.stop();
    }
  }

  @Test
  void resolvesALabelledNamespaceToItsOwningGroup() {
    createNamespace("platform-dev", Map.of("xyz.com/team", "platform"));

    var found = started().find("platform-dev");

    assertThat(found)
        .contains(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev"));
  }

  @Test
  void excludesNamespacesWithNoLabels() {
    createNamespace("kube-system", Map.of());

    assertThat(started().find("kube-system")).isEmpty();
  }

  @Test
  void excludesNamespacesLabelledWithADifferentKey() {
    createNamespace("other", Map.of("example.com/team", "platform"));

    assertThat(started().find("other")).isEmpty();
  }

  @Test
  void excludesNamespacesWhoseTeamLabelIsBlank() {
    createNamespace("blank", Map.of("xyz.com/team", "   "));

    assertThat(started().find("blank")).isEmpty();
  }

  @Test
  void listsOnlyLabelledNamespaces() {
    createNamespace("platform-dev", Map.of("xyz.com/team", "platform"));
    createNamespace("payments-dev", Map.of("xyz.com/team", "payments"));
    createNamespace("kube-system", Map.of());

    assertThat(started().all())
        .extracting(NamespaceInfo::name)
        .containsExactlyInAnyOrder("platform-dev", "payments-dev");
  }

  @Test
  void picksUpANamespaceCreatedAfterItStarted() {
    KubernetesNamespaceCatalog running = started();
    createNamespace("late-dev", Map.of("xyz.com/team", "late"));

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> running.find("late-dev").isPresent());

    assertThat(running.find("late-dev")).map(NamespaceInfo::owningGroup).contains("ad-late-dev");
  }

  @Test
  void staysUnreadyWhenTheInformerCannotStart() {
    // Losing sight of the cluster must not look like a cluster with no
    // labelled namespaces, so the catalog reports unready and denies.
    KubernetesClient broken = org.mockito.Mockito.mock(KubernetesClient.class);
    org.mockito.Mockito.when(broken.namespaces())
        .thenThrow(new io.fabric8.kubernetes.client.KubernetesClientException("no API server"));
    LogGateProperties properties =
        new LogGateProperties(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"));
    var failing =
        new KubernetesNamespaceCatalog(broken, new GroupNameRenderer(properties), properties);

    failing.start();

    assertThat(failing.isReady()).isFalse();
    assertThat(failing.all()).isEmpty();
    failing.stop();
  }

  @Test
  void reportsNotReadyAndResolvesNothingBeforeItStarts() {
    LogGateProperties properties =
        new LogGateProperties(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"));
    var unstarted =
        new KubernetesNamespaceCatalog(client, new GroupNameRenderer(properties), properties);

    assertThat(unstarted.isReady()).isFalse();
    assertThat(unstarted.find("platform-dev")).isEmpty();
    assertThat(unstarted.all()).isEmpty();
    unstarted.stop();
  }
}
