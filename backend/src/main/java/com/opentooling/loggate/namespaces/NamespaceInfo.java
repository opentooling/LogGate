package com.opentooling.loggate.namespaces;

/**
 * A namespace that carries a team label, together with the group entitled to
 * export it. A namespace without a usable team label is never represented here:
 * absence is what makes authorization fail closed.
 *
 * @param name the namespace name
 * @param team the value of the team label
 * @param owningGroup the group rendered from {@code team}
 */
public record NamespaceInfo(String name, String team, String owningGroup) {}
