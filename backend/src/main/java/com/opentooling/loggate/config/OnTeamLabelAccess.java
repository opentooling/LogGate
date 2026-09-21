package com.opentooling.loggate.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when namespaces are granted by team label, the only mode that reads
 * the Kubernetes API.
 *
 * <p>The setting is bound the same way the application binds it, rather than
 * compared as a string, so {@code team-label}, {@code TEAM_LABEL} and
 * {@code teamLabel} all mean the same here as they do everywhere else. A
 * string comparison would let a spelling that the application accepts switch
 * the Kubernetes client off behind its back.
 */
class OnTeamLabelAccess implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return Binder.get(context.getEnvironment())
        .bind("loggate.access.mode", LogGateProperties.AccessMode.class)
        .orElse(LogGateProperties.AccessMode.TEAM_LABEL)
        == LogGateProperties.AccessMode.TEAM_LABEL;
  }
}
