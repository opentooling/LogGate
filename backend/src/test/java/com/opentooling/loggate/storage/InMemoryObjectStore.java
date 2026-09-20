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
  public String presignedUrl(String key, java.time.Duration validFor) {
    return "https://storage.test/" + key + "?expires=" + validFor.toSeconds();
  }

  @Override
  public java.io.InputStream open(String key) {
    byte[] content = objects.get(key);
    if (content == null) {
      throw new IllegalArgumentException("no such object: " + key);
    }
    return new java.io.ByteArrayInputStream(content);
  }

  @Override
  public long size(String key) {
    byte[] content = objects.get(key);
    if (content == null) {
      throw new IllegalArgumentException("no such object: " + key);
    }
    return content.length;
  }

  @Override
  public void deletePrefix(String prefix) {
    if (failure != null) {
      throw failure;
    }
    objects.keySet().removeIf(key -> key.startsWith(prefix));
  }
}
