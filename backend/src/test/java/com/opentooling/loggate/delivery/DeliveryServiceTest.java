package com.opentooling.loggate.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.opentooling.loggate.config.TestProperties;
import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.jobs.JobState;
import com.opentooling.loggate.storage.InMemoryObjectStore;
import com.opentooling.loggate.storage.PartKeys;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class DeliveryServiceTest {

  private static final UUID JOB = UUID.randomUUID();

  private final ExportJobRepository jobs = mock(ExportJobRepository.class);
  private final InMemoryObjectStore store = new InMemoryObjectStore();
  private final DeliveryService delivery =
      new DeliveryService(jobs, store, TestProperties.defaults());

  private static ExportJob job() {
    return new ExportJob(
        JOB,
        "alice-subject",
        JobState.READY,
        null,
        null,
        List.of("platform-dev"),
        "{namespace=\"platform-dev\"}",
        Instant.parse("2026-09-20T00:00:00Z"),
        Instant.parse("2026-09-20T06:00:00Z"),
        100,
        200,
        1,
        1,
        120,
        4,
        false,
        Instant.parse("2026-09-20T07:00:00Z"),
        Instant.parse("2026-09-20T07:05:00Z"),
        Instant.parse("2026-09-22T07:05:00Z"));
  }

  private static long crc(byte[] content) {
    var crc = new CRC32();
    crc.update(content);
    return crc.getValue();
  }

  /** Puts a part in the store and tells the repository about it. */
  private byte[] givenPart(int index, String content, boolean withCrc) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String key = PartKeys.part(JOB, index);
    store.put(key, out -> out.write(bytes));
    when(jobs.listArtifacts(JOB, "PART"))
        .thenReturn(
            List.of(
                new ExportJobRepository.Artifact(
                    key, bytes.length, "sha-" + index, withCrc ? crc(bytes) : null)));
    return bytes;
  }

  private void givenManifest(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String key = DeliveryService.manifestKey(JOB);
    store.put(key, out -> out.write(bytes));
    when(jobs.listArtifacts(JOB, "MANIFEST"))
        .thenReturn(
            List.of(
                new ExportJobRepository.Artifact(key, bytes.length, "sha-manifest", crc(bytes))));
  }

  @Test
  void listsEveryFileWithAShortLivedUrl() {
    givenManifest("{}");
    givenPart(0, "part-zero", true);

    List<DeliveryService.Download> downloads = delivery.downloadsFor(job());

    assertThat(downloads).hasSize(2);
    // The manifest comes first, so a reader meets the description before the data.
    assertThat(downloads.getFirst().name()).isEqualTo("manifest.json");
    assertThat(downloads.getLast().name()).isEqualTo("000000.jsonl.gz");
    assertThat(downloads.getLast().url()).contains("expires=1800");
  }

  @Test
  void generatesAScriptThatDownloadsAndVerifies() {
    // Tens of gigabytes is not a browser activity: a script that resumes,
    // retries and checks checksums is more use than a page of links.
    givenManifest("{}");
    givenPart(0, "part-zero", true);

    String script = delivery.downloadScript(job(), delivery.downloadsFor(job()));

    assertThat(script)
        .contains("--continue-at -")
        .contains("--retry 5")
        .contains("shasum -a 256 -c SHA256SUMS")
        .contains("sha-0  000000.jsonl.gz")
        .contains(JOB.toString());
  }

  @Test
  void omitsFromTheChecksumListAnythingWithoutAChecksum() {
    when(jobs.listArtifacts(JOB, "MANIFEST"))
        .thenReturn(List.of(new ExportJobRepository.Artifact("jobs/x/manifest.json", 2, null, 1L)));
    when(jobs.listArtifacts(JOB, "PART")).thenReturn(List.of());
    store.put("jobs/x/manifest.json", out -> out.write("{}".getBytes(StandardCharsets.UTF_8)));

    String script = delivery.downloadScript(job(), delivery.downloadsFor(job()));

    assertThat(script).contains("SHA256SUMS").doesNotContain("null  manifest.json");
  }

  @Test
  void streamsAnArchiveThatStoresTheAlreadyCompressedParts() throws IOException {
    // Re-deflating gzip would cost CPU to make it marginally larger, so the
    // entries are STOREd - which is only possible because the CRC was recorded
    // when the part was written.
    givenManifest("{\"jobId\":\"x\"}");
    byte[] part = givenPart(0, "part-zero-content", true);
    var out = new ByteArrayOutputStream();

    delivery.streamArchive(job(), out);

    List<ZipEntry> entries = new ArrayList<>();
    byte[] recovered = null;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.add(entry);
        if (entry.getName().endsWith(".gz")) {
          recovered = zip.readAllBytes();
        }
      }
    }

    assertThat(entries).extracting(ZipEntry::getName).containsExactly("manifest.json", "000000.jsonl.gz");
    assertThat(entries.getLast().getMethod()).isEqualTo(ZipEntry.STORED);
    assertThat(recovered).isEqualTo(part);
  }

  @Test
  void stillDeliversAPartThatHasNoRecordedChecksum() {
    // Written before CRCs were recorded: deflating is slower but correct, and
    // better than refusing the download.
    when(jobs.listArtifacts(JOB, "MANIFEST")).thenReturn(List.of());
    byte[] part = givenPart(0, "legacy part", false);
    var out = new ByteArrayOutputStream();

    org.assertj.core.api.Assertions.assertThatCode(() -> delivery.streamArchive(job(), out))
        .doesNotThrowAnyException();

    assertThat(out.size()).isGreaterThan(part.length / 2);
  }

  @Test
  void namesFilesAfterTheirKeyWithoutThePath() {
    assertThat(DeliveryService.fileNameOf("jobs/abc/parts/000007.jsonl.gz"))
        .isEqualTo("000007.jsonl.gz");
    assertThat(DeliveryService.manifestKey(JOB)).endsWith("/manifest.json");
  }
}
