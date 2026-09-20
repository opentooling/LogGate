package com.opentooling.loggate.export;

import java.time.Instant;

/**
 * Conversions to and from Loki's nanosecond timestamps.
 *
 * <p>Paging compares these values exactly, so they are carried as raw longs
 * rather than being round-tripped through anything coarser.
 */
public final class Timestamps {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private Timestamps() {}

  /** Nanoseconds since the epoch. */
  public static long toNanos(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
  }

  /** The instant at {@code nanos} since the epoch. */
  public static Instant fromNanos(long nanos) {
    return Instant.ofEpochSecond(
        Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND));
  }
}
