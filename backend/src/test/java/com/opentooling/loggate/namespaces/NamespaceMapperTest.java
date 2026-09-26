package com.opentooling.loggate.namespaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.authz.GroupNameRenderer;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rule that decides who owns a namespace, tested without a cluster. */
class NamespaceMapperTest {

  private static final LogGateProperties PROPERTIES =
      TestProperties.of(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev", ""),
            new LogGateProperties.Loki("http://loki.test", "", 5000, java.time.Duration.ofSeconds(30), "", ""),
            new LogGateProperties.Windows(268435456L, java.time.Duration.ofMinutes(1), java.time.Duration.ofHours(1), 5000));

  private final NamespaceMapper mapper =
      new NamespaceMapper(new GroupNameRenderer(PROPERTIES), "xyz.com/team");

  private static Namespace namespace(String name, Map<String, String> labels) {
    return new NamespaceBuilder()
        .withNewMetadata()
        .withName(name)
        .withLabels(labels)
        .endMetadata()
        .build();
  }

  @Test
  void mapsALabelledNamespaceToItsOwningGroup() {
    assertThat(mapper.map(namespace("platform-dev", Map.of("xyz.com/team", "platform"))))
        .contains(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev"));
  }

  @Test
  void rejectsNull() {
    assertThat(mapper.map(null)).isEmpty();
  }

  @Test
  void rejectsANamespaceWithNoMetadata() {
    Namespace withoutMetadata = new Namespace();
    withoutMetadata.setMetadata(null);

    assertThat(mapper.map(withoutMetadata)).isEmpty();
  }

  @Test
  void rejectsANamespaceWithNoLabelsAtAll() {
    Namespace ns = new Namespace();
    ObjectMeta meta = new ObjectMeta();
    meta.setName("kube-system");
    meta.setLabels(null);
    ns.setMetadata(meta);

    assertThat(mapper.map(ns)).isEmpty();
  }

  @Test
  void rejectsANamespaceWithoutTheTeamLabel() {
    assertThat(mapper.map(namespace("other", Map.of("example.com/team", "platform")))).isEmpty();
  }

  @Test
  void rejectsABlankTeamLabel() {
    // A blank label would render a group like "ad--dev", which could plausibly
    // exist. An unnamed owner is no owner.
    assertThat(mapper.map(namespace("blank", Map.of("xyz.com/team", "  ")))).isEmpty();
  }
}
