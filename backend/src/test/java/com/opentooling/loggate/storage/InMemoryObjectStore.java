package com.opentooling.loggate.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** An {@link ObjectStore} in a map, so worker behaviour can be tested without S3. */
public class InMemoryObjectStore implements ObjectStore {

  private final Map<String, byte[]> objects = new LinkedHashMap<>();
  private RuntimeException failure;

  /** Makes every subsequent operation fail. */
  public InMemoryObjectStore failWith(RuntimeException failure) {
    this.failure = failure;
    return this;
  }

  /** The objects currently held. */
  public Map<String, byte[]> objects() {
    return Map.copyOf(objects);
  }

  @Override
  public long put(String key, ContentWriter writer) {
    if (failure != null) {
      throw failure;
    }
    var buffer = new ByteArrayOutputStream();
    try {
      writer.writeTo(buffer);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    objects.put(key, buffer.toByteArray());
    return buffer.size();
  }

  @Override
  public void deletePrefix(String prefix) {
    if (failure != null) {
      throw failure;
    }
    objects.keySet().removeIf(key -> key.startsWith(prefix));
  }
}
