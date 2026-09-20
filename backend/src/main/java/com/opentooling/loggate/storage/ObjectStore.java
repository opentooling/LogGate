package com.opentooling.loggate.storage;

import java.io.IOException;
import java.io.OutputStream;

/** Where export artifacts are written. */
public interface ObjectStore {

  /** Writes bytes to a stream the store provides. */
  @FunctionalInterface
  interface ContentWriter {
    void writeTo(OutputStream out) throws IOException;
  }

  /**
   * Streams content to {@code key}, replacing anything already there.
   *
   * <p>Replacing rather than appending is what makes a retried window safe: the
   * key is derived from the job and window index, so running it again produces
   * the same object instead of duplicating its contents.
   *
   * @return how many bytes were written
   */
  long put(String key, ContentWriter writer);

  /** Removes every object under a prefix, for retention and cancellation. */
  void deletePrefix(String prefix);

  /**
   * A URL that downloads {@code key} directly from object storage.
   *
   * <p>Bulk downloads must not pass through the control plane: tens of
   * gigabytes through an application process is wasted bandwidth and a
   * needless failure point. The URL is short-lived, and entitlement is checked
   * when it is minted, not only when the export was submitted.
   */
  String presignedUrl(String key, java.time.Duration validFor);

  /** Opens {@code key} for reading, for the proxied archive path. */
  java.io.InputStream open(String key);

  /** Size of {@code key} in bytes. */
  long size(String key);
}
