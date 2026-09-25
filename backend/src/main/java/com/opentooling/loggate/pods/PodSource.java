package com.opentooling.loggate.pods;

import java.time.Instant;
import java.util.List;

/** Where the pods on offer come from. */
public interface PodSource {

  /** Whether pods can be listed at all. */
  boolean enabled();

  /**
   * The pods that existed in {@code namespaces} between {@code from} and
   * {@code to}, within {@code clusters} when any are named.
   *
   * @throws MetricsException when the source cannot answer
   */
  PodListing list(List<String> clusters, List<String> namespaces, Instant from, Instant to);
}
