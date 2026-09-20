package com.opentooling.loggate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for how namespaces map to the groups allowed to export them.
 *
 * @param namespaces namespace-to-team-to-group resolution settings
 */
@ConfigurationProperties("loggate")
public record LogGateProperties(@DefaultValue Namespaces namespaces) {

  /**
   * @param enabled whether to resolve namespaces from the Kubernetes API; when
   *     false the catalog is permanently unready and every export is refused
   * @param labelKey namespace label naming the owning team, e.g. {@code xyz.com/team}
   * @param groupTemplate template rendering the owning group from the team, with
   *     {@code {team}} and {@code {env}} placeholders, e.g. {@code ad-{team}-{env}}
   * @param environment value substituted for {@code {env}}
   */
  public record Namespaces(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("xyz.com/team") String labelKey,
      @DefaultValue("ad-{team}-{env}") String groupTemplate,
      @DefaultValue("dev") String environment) {}
}
