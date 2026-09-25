package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.authz.OpenAccess;
import com.opentooling.loggate.authz.TeamLabelAccess;
import com.opentooling.loggate.loki.FakeLokiClient;
import com.opentooling.loggate.namespaces.FakeNamespaceCatalog;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Which access mode is built, and which configurations refuse to start. */
class AuthorizationConfigTest {

  private static final FakeNamespaceCatalog CATALOG = new FakeNamespaceCatalog();

  private static LogGateProperties properties(
      LogGateProperties.AccessMode mode, String role, String clusterLabel, String localCluster) {
    return new LogGateProperties(
        new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev", localCluster),
        new LogGateProperties.Loki("http://loki.test", "", 5000, Duration.ofSeconds(30), clusterLabel),
        TestProperties.windows(),
        TestProperties.quotas(),
        TestProperties.execution(),
        TestProperties.storage(),
        new LogGateProperties.Access(
            mode, role, Duration.ofDays(7), Duration.ofSeconds(60), "loggate-admin"),
        TestProperties.oidc(),
        TestProperties.pods());
  }

  private static Object build(LogGateProperties properties) {
    return AuthorizationConfig.namespaceAccess(
        properties, new NamespaceAuthorizer(CATALOG), CATALOG, new FakeLokiClient(), "loggate", Clock.systemUTC());
  }

  @Test
  void buildsTeamLabelAccessByDefault() {
    assertThat(build(properties(LogGateProperties.AccessMode.TEAM_LABEL, "", "", "")))
        .isInstanceOf(TeamLabelAccess.class);
  }

  @Test
  void buildsTeamLabelAccessPinnedToItsClusterWhenThereIsAClusterLabel() {
    assertThat(build(properties(LogGateProperties.AccessMode.TEAM_LABEL, "", "cluster", "core-eu")))
        .isInstanceOf(TeamLabelAccess.class);
  }

  @Test
  void refusesTeamLabelAccessWithAClusterLabelButNoClusterToPinTo() {
    // Without it, a team's namespace name would match the same name in every
    // cluster Loki holds.
    assertThatThrownBy(() -> build(properties(LogGateProperties.AccessMode.TEAM_LABEL, "", "cluster", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("loggate.namespaces.cluster");
  }

  @Test
  void buildsOpenAccessWithARole() {
    assertThat(build(properties(LogGateProperties.AccessMode.OPEN, "export-logs", "cluster", "")))
        .isInstanceOf(OpenAccess.class);
  }

  @Test
  void refusesOpenAccessWithoutARole() {
    assertThatThrownBy(() -> build(properties(LogGateProperties.AccessMode.OPEN, "", "cluster", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("open-role");
  }

  @Test
  void refusesAClusterLabelThatIsNotALabelName() {
    assertThatThrownBy(
            () -> build(properties(LogGateProperties.AccessMode.OPEN, "export-logs", "k8s.cluster", "")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cluster-label");
  }

  @Configuration(proxyBeanMethods = false)
  @Conditional(OnTeamLabelAccess.class)
  static class OnlyInTeamLabelMode {
    @Bean
    String marker() {
      return "team-label";
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OnlyInTeamLabelMode.class);

  @Test
  void readsTheKubernetesApiOnlyInTeamLabelMode() {
    runner.run(context -> assertThat(context).hasBean("marker"));
    runner
        .withPropertyValues("loggate.access.mode=TEAM_LABEL")
        .run(context -> assertThat(context).hasBean("marker"));
    runner
        .withPropertyValues("loggate.access.mode=OPEN")
        .run(context -> assertThat(context).doesNotHaveBean("marker"));
  }

  @Test
  void understandsTheModeHoweverItIsSpelled() {
    // Bound, not string-compared: every spelling the application accepts means
    // the same thing to the condition, so none can switch Kubernetes off.
    runner
        .withPropertyValues("loggate.access.mode=team-label")
        .run(context -> assertThat(context).hasBean("marker"));
    runner
        .withPropertyValues("loggate.access.mode=open")
        .run(context -> assertThat(context).doesNotHaveBean("marker"));
  }
}
