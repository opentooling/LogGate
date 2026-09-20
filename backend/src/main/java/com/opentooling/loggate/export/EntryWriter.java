package com.opentooling.loggate.export;

import com.opentooling.loggate.loki.LogEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes entries as gzipped JSON lines.
 *
 * <p>One JSON object per line, so the result is readable with zcat, jq, grep or
 * any log tool, and can be split without parsing. Gzip because the parts are
 * later concatenated: gzip members join bytewise into a valid gzip file, which
 * is what lets object storage assemble per-stream files server-side rather than
 * downloading and recompressing them.
 */
public class EntryWriter implements AutoCloseable {

  private final ObjectMapper json;
  private final GZIPOutputStream gzip;
  private final Writer writer;
  private long entries;
  private long uncompressedBytes;

  public EntryWriter(OutputStream out, ObjectMapper json) {
    this.json = json;
    try {
      this.gzip = new GZIPOutputStream(out, 64 * 1024);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.writer = new java.io.OutputStreamWriter(gzip, StandardCharsets.UTF_8);
  }

  /** Writes one entry. */
  public void write(LogEntry entry) {
    String line =
        json.writeValueAsString(
            Map.of(
                "timestamp", Long.toString(entry.timestampNanos()),
                "labels", entry.labels(),
                "line", entry.line()));
    try {
      writer.write(line);
      writer.write('\n');
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    entries++;
    uncompressedBytes += line.length() + 1L;
  }

  /** How many entries have been written. */
  public long entries() {
    return entries;
  }

  /**
   * Uncompressed bytes written so far.
   *
   * <p>Quotas are counted uncompressed because that is what the volume estimate
   * reports, so admission and enforcement speak the same units.
   */
  public long uncompressedBytes() {
    return uncompressedBytes;
  }

  @Override
  public void close() {
    try {
      writer.flush();
      gzip.finish();
      writer.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
