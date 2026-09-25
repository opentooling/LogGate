package com.opentooling.loggate.delivery;

import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.ExportJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Assembles an export's manifest from what the windows actually produced. */
public class ManifestBuilder {

  /**
   * How close to the present a range has to reach before the manifest warns
   * about it. Loki serves recent entries from ingesters that may not have
   * flushed, so an export ending a minute ago can legitimately be incomplete.
   */
  private static final Duration RECENT = Duration.ofMinutes(15);

  private final ExportJobRepository jobs;
  private final Clock clock;

  public ManifestBuilder(ExportJobRepository jobs, Clock clock) {
    this.jobs = jobs;
    this.clock = clock;
  }

  /** Builds the manifest for a finished job. */
  public Manifest build(ExportJob job) {
    Map<String, ExportJobRepository.Artifact> byKey =
        jobs.listArtifacts(job.id(), "PART").stream()
            .collect(Collectors.toMap(ExportJobRepository.Artifact::key, Function.identity()));

    List<Manifest.Part> parts = new ArrayList<>();
    for (var window : jobs.windowSummaries(job.id())) {
      String key =
          com.opentooling.loggate.storage.PartKeys.part(job.id(), window.index(), job.format());
      var artifact = byKey.get(key);
      if (artifact == null) {
        // No part for a window means the window never completed, which cannot
        // happen for a READY job. Leaving it out silently would be worse than
        // the manifest simply not listing it.
        continue;
      }
      parts.add(
          new Manifest.Part(
              window.index(),
              key,
              window.from(),
              window.to(),
              window.entries(),
              window.uncompressedBytes(),
              artifact.sizeBytes(),
              artifact.sha256()));
    }

    return new Manifest(
        job.id(),
        job.selector(),
        job.namespaces(),
        job.from(),
        job.to(),
        job.entriesWritten(),
        job.bytesWritten(),
        "gzipped JSON lines, one object per line, chronological across parts",
        parts,
        caveats(job),
        clock.instant());
  }

  /**
   * What a reader should know before trusting the contents.
   *
   * <p>Stating a known limit is worth more than a manifest that quietly implies
   * completeness it cannot guarantee.
   */
  private List<String> caveats(ExportJob job) {
    List<String> caveats = new ArrayList<>();
    Instant now = clock.instant();
    if (Duration.between(job.to(), now).compareTo(RECENT) < 0) {
      caveats.add(
          "The range reaches within "
              + RECENT.toMinutes()
              + " minutes of the export. Entries still held in Loki's ingesters may be missing;"
              + " re-run later for a complete picture of that period.");
    }
    // Log bytes, the estimate's units: what was written includes the JSON
    // around each line, so would exceed every estimate.
    if (job.logBytes() > job.estimatedBytes() && job.estimatedBytes() > 0) {
      caveats.add(
          "More was exported than the pre-flight estimate predicted, which is normal for a"
              + " namespace still receiving logs during the export.");
    }
    return caveats;
  }
}
