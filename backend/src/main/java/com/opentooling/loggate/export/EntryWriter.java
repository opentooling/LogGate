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
 * Writes entries as gzipped lines, as JSON or as the raw log lines.
 *
 * <p>One entry per line either way, so the result is readable with zcat, jq,
 * grep or any log tool, and can be split without parsing. Gzip because the parts are
 * later concatenated: gzip members join bytewise into a valid gzip file, which
 * is what lets object storage assemble per-stream files server-side rather than
 * downloading and recompressing them.
 */
public class EntryWriter implements AutoCloseable {

  private final ObjectMapper json;
  private final OutputFormat format;
  private final GZIPOutputStream gzip;
  private final Writer writer;
  private long entries;
  private long uncompressedBytes;
  private long logBytes;

  public EntryWriter(OutputStream out, ObjectMapper json) {
    this(out, json, OutputFormat.JSON);
  }

  public EntryWriter(OutputStream out, ObjectMapper json, OutputFormat format) {
    this.json = json;
    this.format = format;
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
        format == OutputFormat.RAW
            ? withoutLineEnding(entry.line())
            : json.writeValueAsString(
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
    uncompressedBytes += utf8Length(line) + 1L;
    logBytes += utf8Length(entry.line());
  }

  /**
   * The line without the one line ending it may have kept.
   *
   * <p>Collected from a container, a line usually arrives with the newline its
   * program wrote, and this writer ends every entry with one of its own; both
   * would put a blank line after each entry. Only the last is removed, so a
   * multi-line entry such as a stack trace keeps its inner lines. JSON keeps
   * the line exactly as stored, since it is quoted there and does no harm.
   */
  static String withoutLineEnding(String line) {
    if (line.endsWith("\r\n")) {
      return line.substring(0, line.length() - 2);
    }
    if (line.endsWith("\n")) {
      return line.substring(0, line.length() - 1);
    }
    return line;
  }

  /** How many entries have been written. */
  public long entries() {
    return entries;
  }

  /**
   * Uncompressed bytes written so far: the size of the files once unzipped.
   *
   * <p>For JSON lines this is two to three times {@link #logBytes()}, since each
   * entry carries its labels and timestamp, so it is what is reported rather
   * than what quotas are held to.
   */
  public long uncompressedBytes() {
    return uncompressedBytes;
  }

  /**
   * Bytes of log lines read so far, as Loki counts them.
   *
   * <p>The estimate is in these units, so the byte cap and the team budget are
   * too: admission and enforcement measure the same thing, whichever format
   * the files are written in.
   */
  public long logBytes() {
    return logBytes;
  }

  /** The UTF-8 length of {@code text}, without encoding it. */
  static long utf8Length(CharSequence text) {
    long length = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x80) {
        length += 1;
      } else if (c < 0x800) {
        length += 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        length += 4;
        i++;
      } else {
        length += 3;
      }
    }
    return length;
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
