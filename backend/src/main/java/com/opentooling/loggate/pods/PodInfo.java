package com.opentooling.loggate.pods;

/**
 * A pod on offer.
 *
 * @param namespace the namespace it runs in
 * @param name its name
 */
public record PodInfo(String namespace, String name) {}
