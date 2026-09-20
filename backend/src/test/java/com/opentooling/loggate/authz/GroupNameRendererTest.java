package com.opentooling.loggate.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import org.junit.jupiter.api.Test;

class GroupNameRendererTest {

  private static GroupNameRenderer renderer(String template, String environment) {
    return new GroupNameRenderer(
        TestProperties.of(
            new LogGateProperties.Namespaces(true, "xyz.com/team", template, environment),
            new LogGateProperties.Loki("http://loki.test", "", 5000, java.time.Duration.ofSeconds(30)),
            new LogGateProperties.Windows(268435456L, java.time.Duration.ofMinutes(1), java.time.Duration.ofHours(1), 5000)));
  }

  @Test
  void rendersTeamAndEnvironment() {
    assertThat(renderer("ad-{team}-{env}", "dev").render("platform")).isEqualTo("ad-platform-dev");
  }

  @Test
  void rendersTemplatesThatDoNotUseTheEnvironment() {
    assertThat(renderer("team-{team}", "prod").render("payments")).isEqualTo("team-payments");
  }

  @Test
  void substitutesEveryOccurrence() {
    assertThat(renderer("{team}-{env}-{team}", "dev").render("core")).isEqualTo("core-dev-core");
  }

  @Test
  void refusesATemplateWithoutTheTeamPlaceholder() {
    // Such a template renders one group for every team, silently granting every
    // team access to every namespace. Failing to start is the safe outcome.
    assertThatThrownBy(() -> renderer("ad-shared-{env}", "dev"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("{team}");
  }
}
