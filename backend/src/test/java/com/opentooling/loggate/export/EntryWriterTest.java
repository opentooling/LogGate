package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.loki.LogEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EntryWriterTest {

  private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

  private String written() {
    try (var in = new GZIPInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void writesOneJsonObjectPerLine() {
    // One object per line, so the result works with zcat, jq or grep and can be
    // split without parsing.
    try (var writer = new EntryWriter(sink, JsonMapper.builder().build())) {
      writer.write(new LogEntry(100, "first", Map.of("pod", "api-0")));
      writer.write(new LogEntry(200, "second", Map.of("pod", "api-1")));
    }

    assertThat(written().lines()).hasSize(2);
    assertThat(written()).contains("\"timestamp\":\"100\"").contains("\"line\":\"second\"");
  }

  @Test
  void countsEntriesAndUncompressedBytes() {
    // Uncompressed, because that is the unit the volume estimate reports, so
    // admission and enforcement speak the same language.
    try (var writer = new EntryWriter(sink, JsonMapper.builder().build())) {
      writer.write(new LogEntry(100, "hello", Map.of()));
      writer.write(new LogEntry(200, "world", Map.of()));

      assertThat(writer.entries()).isEqualTo(2);
      assertThat(writer.uncompressedBytes()).isGreaterThan(20);
    }
  }

  @Test
  void producesAValidEmptyArchiveWhenNothingIsWritten() {
    try (var writer = new EntryWriter(sink, JsonMapper.builder().build())) {
      assertThat(writer.entries()).isZero();
    }

    assertThat(written()).isEmpty();
  }

  @Test
  void surfacesAStorageFailureRatherThanSwallowingIt() {
    // If the underlying upload fails mid-window, the worker must find out.
    var broken =
        new java.io.OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("the bucket went away");
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("the bucket went away");
          }
        };

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> {
              var writer = new EntryWriter(broken, JsonMapper.builder().build());
              for (int i = 0; i < 20_000; i++) {
                writer.write(new LogEntry(i, "a line long enough to force a flush " + i, Map.of()));
              }
              writer.close();
            })
        .isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void escapesContentThatWouldOtherwiseBreakTheLineFormat() {
    try (var writer = new EntryWriter(sink, JsonMapper.builder().build())) {
      writer.write(new LogEntry(100, "a line\nwith a newline and a \" quote", Map.of()));
    }

    // The embedded newline must not split the record into two lines.
    assertThat(written().lines()).hasSize(1);
  }

  @Test
  void writesTheRawLinesAloneInRawFormat() {
    try (var writer = new EntryWriter(sink, JsonMapper.builder().build(), OutputFormat.RAW)) {
      writer.write(new LogEntry(1L, "GET /health 200", Map.of("pod", "api-1")));
      writer.write(new LogEntry(2L, "{\"already\":\"json\"}", Map.of("pod", "api-2")));
      // Counted as written, which in this format is the line and its newline.
      assertThat(writer.uncompressedBytes()).isEqualTo(16 + 19);
    }
    // Exactly as logged: no timestamp, no labels, no quoting.
    assertThat(written()).isEqualTo("GET /health 200\n{\"already\":\"json\"}\n");
  }
}
