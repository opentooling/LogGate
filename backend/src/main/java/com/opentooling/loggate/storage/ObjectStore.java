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
}
