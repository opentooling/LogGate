package com.opentooling.loggate.pods;

import java.time.Instant;
import java.util.List;

/** No source configured: pods are narrowed by pattern only. */
public class NoPodSource implements PodSource {

  @Override
  public boolean enabled() {
    return false;
  }

  @Override
  public PodListing list(List<String> clusters, List<String> namespaces, Instant from, Instant to) {
    return PodListing.unavailable();
  }
}
