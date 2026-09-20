package com.opentooling.loggate.delivery;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.storage.ObjectStore;
import com.opentooling.loggate.storage.PartKeys;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hands a finished export to the person who asked for it.
 *
 * <p>Two paths, because one size genuinely does not fit. Presigned URLs keep
 * tens of gigabytes out of the control plane entirely, which is the only
 * sensible way to move an export of that size. A proxied archive is for the
 * case where someone just wants a file, and is capped accordingly.
 */
public class DeliveryService {

  private final ExportJobRepository jobs;
  private final ObjectStore store;
  private final Duration urlLifetime;

  public DeliveryService(
      ExportJobRepository jobs, ObjectStore store, LogGateProperties properties) {
    this.jobs = jobs;
    this.store = store;
    this.urlLifetime = properties.storage().presignedUrlLifetime();
  }

  /**
   * A downloadable artifact.
   *
   * @param name file name to save it as
   * @param key object key
   * @param sizeBytes stored size
   * @param sha256 checksum of the stored bytes
   * @param url a short-lived direct download URL
   */
  public record Download(String name, String key, long sizeBytes, String sha256, String url) {}

  /**
   * Every file of a finished export, with a fresh download URL each time.
   *
   * <p>The URLs are minted per request rather than stored, so entitlement is
   * re-checked at download time: an artifact must not outlive the access that
   * produced it.
   */
  public List<Download> downloadsFor(ExportJob job) {
    List<Download> downloads = new ArrayList<>();
    for (String kind : List.of("MANIFEST", "PART")) {
      for (var artifact : jobs.listArtifacts(job.id(), kind)) {
        downloads.add(
            new Download(
                fileNameOf(artifact.key()),
                artifact.key(),
                artifact.sizeBytes(),
                artifact.sha256(),
                store.presignedUrl(artifact.key(), urlLifetime)));
      }
    }
    return downloads;
  }

  /**
   * A script that downloads every file and verifies it.
   *
   * <p>Tens of gigabytes is not a browser activity. Handing over a script that
   * resumes, retries and checks the checksums is more useful than a page of
   * links someone will click one at a time.
   */
  public String downloadScript(ExportJob job, List<Download> downloads) {
    StringBuilder script = new StringBuilder();
    script
        .append("#!/usr/bin/env bash\n")
        .append("# LogGate export ")
        .append(job.id())
        .append("\n# ")
        .append(job.selector())
        .append("\n# ")
        .append(job.from())
        .append(" .. ")
        .append(job.to())
        .append("\n#\n# These URLs expire ")
        .append(urlLifetime.toMinutes())
        .append(" minutes after this script was generated.\n")
        .append("set -euo pipefail\n\n")
        .append("mkdir -p loggate-")
        .append(job.id())
        .append("\ncd loggate-")
        .append(job.id())
        .append("\n\n");
    for (Download download : downloads) {
      script
          .append("curl --fail --location --retry 5 --continue-at - --output '")
          .append(download.name())
          .append("' '")
          .append(download.url())
          .append("'\n");
    }
    script.append("\n# Verify what was downloaded\ncat > SHA256SUMS <<'SUMS'\n");
    for (Download download : downloads) {
      if (download.sha256() != null) {
        script.append(download.sha256()).append("  ").append(download.name()).append('\n');
      }
    }
    script
        .append("SUMS\n")
        .append("shasum -a 256 -c SHA256SUMS\n\n")
        .append("# Read them in order:\n")
        .append("#   zcat *.jsonl.gz | jq .\n");
    return script.toString();
  }

  /**
   * Streams the whole export as a ZIP.
   *
   * <p>Entries are STOREd rather than deflated: the parts are already gzipped,
   * so compressing them again would cost CPU to make them marginally larger.
   * That requires each entry's size and CRC up front, which is why both are
   * recorded when the part is written.
   *
   * <p>Java's ZipOutputStream writes ZIP64 headers automatically once an entry
   * or the archive exceeds the 4 GB limit, so large exports stay valid.
   */
  public void streamArchive(ExportJob job, OutputStream out) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.setMethod(ZipOutputStream.STORED);
      for (String kind : List.of("MANIFEST", "PART")) {
        for (var artifact : jobs.listArtifacts(job.id(), kind)) {
          if (artifact.crc32() == null) {
            // Written before the CRC was recorded; deflating it is slower but
            // correct, and better than refusing the download.
            writeDeflated(zip, artifact.key());
            continue;
          }
          ZipEntry entry = new ZipEntry(fileNameOf(artifact.key()));
          entry.setMethod(ZipEntry.STORED);
          entry.setSize(artifact.sizeBytes());
          entry.setCompressedSize(artifact.sizeBytes());
          entry.setCrc(artifact.crc32());
          zip.putNextEntry(entry);
          try (InputStream in = store.open(artifact.key())) {
            in.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
    }
  }

  private void writeDeflated(ZipOutputStream zip, String key) throws IOException {
    ZipEntry entry = new ZipEntry(fileNameOf(key));
    entry.setMethod(ZipEntry.DEFLATED);
    zip.putNextEntry(entry);
    try (InputStream in = store.open(key)) {
      in.transferTo(zip);
    }
    zip.closeEntry();
  }

  /** The file name a key should be saved as. */
  static String fileNameOf(String key) {
    return key.substring(key.lastIndexOf('/') + 1);
  }

  /** The object key of a job's manifest. */
  public static String manifestKey(java.util.UUID jobId) {
    return PartKeys.jobPrefix(jobId) + "manifest.json";
  }
}
