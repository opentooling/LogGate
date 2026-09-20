package com.opentooling.loggate.authz;

import com.opentooling.loggate.config.LogGateProperties;

/**
 * Renders the group name entitled to a team's namespaces, e.g. team
 * {@code platform} in environment {@code dev} with template
 * {@code ad-{team}-{env}} gives {@code ad-platform-dev}.
 */
public class GroupNameRenderer {

  private static final String TEAM_PLACEHOLDER = "{team}";
  private static final String ENV_PLACEHOLDER = "{env}";

  private final String template;
  private final String environment;

  public GroupNameRenderer(LogGateProperties properties) {
    this.template = properties.namespaces().groupTemplate();
    this.environment = properties.namespaces().environment();
    if (!template.contains(TEAM_PLACEHOLDER)) {
      // A template without {team} renders the same group for every team, which
      // would silently grant every team access to every namespace. Refuse to
      // start rather than serve that.
      throw new IllegalArgumentException(
          "loggate.namespaces.group-template must contain " + TEAM_PLACEHOLDER + ": " + template);
    }
  }

  /** The group name owning namespaces labelled with {@code team}. */
  public String render(String team) {
    return template.replace(TEAM_PLACEHOLDER, team).replace(ENV_PLACEHOLDER, environment);
  }
}
