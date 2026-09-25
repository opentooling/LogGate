package com.opentooling.loggate.pods;

import java.util.List;

/**
 * The pods that existed in some namespaces over some range.
 *
 * @param available whether pods can be listed here at all; when not, the pod
 *     pattern is the only way to narrow by pod
 * @param pods the pods, sorted by namespace then name
 * @param truncated whether there were more than the configured maximum, so
 *     {@code pods} is the first of them rather than all
 */
public record PodListing(boolean available, List<PodInfo> pods, boolean truncated) {

  public PodListing {
    pods = List.copyOf(pods);
  }

  /** The answer where pods cannot be listed. */
  public static PodListing unavailable() {
    return new PodListing(false, List.of(), false);
  }
}
