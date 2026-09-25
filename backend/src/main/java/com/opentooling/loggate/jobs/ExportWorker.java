package com.opentooling.loggate.jobs;

import com.opentooling.loggate.export.EntryWriter;
import com.opentooling.loggate.export.ExportWindow;
import com.opentooling.loggate.export.WindowPager;
import com.opentooling.loggate.observability.ExportMetrics;
import com.opentooling.loggate.storage.ObjectStore;
import com.opentooling.loggate.storage.PartKeys;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs one window at a time: claim, page it out of Loki, stream it to object
 * storage, record what it produced.
 *
 * <p>Nothing here coordinates with other workers. The queue hands out windows
 * with {@code SKIP LOCKED}, and a window's object key is derived from its job
 * and index, so two workers running the same window after a lapsed lease
 * produce the same object rather than two copies.
 */
public class ExportWorker {

  private static final Logger log = LoggerFactory.getLogger(ExportWorker.class);

  private final ExportJobRepository jobs;
  private final WindowPager pager;
  private final ObjectStore store;
  private final ObjectMapper json;
  private final String owner;
  private final Duration lease;
  private final int maxAttempts;
  private final long checkEveryEntries;
  private final ExportMetrics metrics;

  public ExportWorker(
      ExportJobRepository jobs,
      WindowPager pager,
      ObjectStore store,
      ObjectMapper json,
      String owner,
      Duration lease,
      int maxAttempts,
      ExportMetrics metrics) {
    this(jobs, pager, store, json, owner, lease, maxAttempts, 5_000, metrics);
  }

  /**
   * @param checkEveryEntries how often to look up from the work to check
   *     cancellation, enforce the byte cap and renew the lease. Frequent enough
   *     to stop promptly, rare enough not to query the database per line.
   */
  public ExportWorker(
      ExportJobRepository jobs,
      WindowPager pager,
      ObjectStore store,
      ObjectMapper json,
      String owner,
      Duration lease,
      int maxAttempts,
      long checkEveryEntries,
      ExportMetrics metrics) {
    this.jobs = jobs;
    this.pager = pager;
    this.store = store;
    this.json = json;
    this.owner = owner;
    this.lease = lease;
    this.maxAttempts = maxAttempts;
    this.checkEveryEntries = checkEveryEntries;
    this.metrics = metrics;
  }

  /**
   * Claims and runs at most one window.
   *
   * @return whether there was anything to do
   */
  public boolean runOnce() {
    var claimed = jobs.claimNext(owner, lease);
    if (claimed.isEmpty()) {
      return false;
    }
    run(claimed.get());
    return true;
  }

  private void run(ClaimedWindow window) {
    String key = PartKeys.part(window.jobId(), window.index(), window.format());
    var counters = new Counters();
    long startedAt = System.nanoTime();
    try {
      long storedBytes =
          store.put(
          key,
          rawOut -> {
            // Checksum the bytes that actually land in storage, so the manifest
            // describes the artifact rather than what was intended.
            java.security.MessageDigest digest = sha256();
            var crc = new java.util.zip.CRC32();
            var out =
                new java.security.DigestOutputStream(
                    new java.util.zip.CheckedOutputStream(rawOut, crc), digest);
            try (EntryWriter writer = new EntryWriter(out, json, window.format())) {
              pager.forEachEntry(
                  window.selector(),
                  new ExportWindow(window.index(), window.from(), window.to()),
                  entry -> {
                    writer.write(entry);
                    if (writer.entries() % checkEveryEntries == 0) {
                      checkStillAllowedToRun(window, writer.uncompressedBytes());
                    }
                  });
              counters.entries = writer.entries();
              counters.bytes = writer.uncompressedBytes();
            }
            counters.sha256 = java.util.HexFormat.of().formatHex(digest.digest());
            counters.crc32 = crc.getValue();
          });
      // The final tally is checked too, so a window that only exceeds the cap
      // at its very end is still caught.
      checkStillAllowedToRun(window, counters.bytes);
      jobs.recordArtifact(
          window.jobId(), "PART", key, storedBytes, counters.sha256, counters.crc32);
      jobs.completeWindow(window.jobId(), window.index(), counters.bytes, counters.entries);
      metrics.windowCompleted(
          Duration.ofNanos(System.nanoTime() - startedAt), counters.entries, counters.bytes);
      log.debug(
          "window {} of job {} wrote {} entries ({} bytes)",
          window.index(),
          window.jobId(),
          counters.entries,
          counters.bytes);

    } catch (ExportCancelledException e) {
      // The job is stopping; leave the window for the closer to tidy up.
      log.info("window {} of job {} abandoned: cancelled", window.index(), window.jobId());
      metrics.windowFailed("cancelled");
      jobs.failWindow(window.jobId(), window.index(), "cancelled", maxAttempts);

    } catch (ByteLimitExceededException e) {
      // Fail the job, not just the window: every other window is now pointless.
      log.warn("job {} exceeded its byte limit", window.jobId());
      metrics.windowFailed("byte_limit");
      metrics.finished("FAILED", FailureCode.BYTE_LIMIT_EXCEEDED.name());
      jobs.failWindow(window.jobId(), window.index(), e.getMessage(), maxAttempts);
      jobs.finish(window.jobId(), JobState.FAILED, FailureCode.BYTE_LIMIT_EXCEEDED, e.getMessage());

    } catch (RuntimeException e) {
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      boolean exhausted = jobs.failWindow(window.jobId(), window.index(), message, maxAttempts);
      FailureCode code =
          e instanceof java.io.UncheckedIOException
              ? FailureCode.STORAGE_FAILED
              : FailureCode.UPSTREAM_FAILED;
      metrics.windowFailed(code.name().toLowerCase(java.util.Locale.ROOT));
      log.warn(
          "window {} of job {} failed on attempt {}{}",
          window.index(),
          window.jobId(),
          window.attempts(),
          exhausted ? ", giving up" : ", will retry",
          e);
      if (exhausted) {
        jobs.finish(window.jobId(), JobState.FAILED, code, message);
        metrics.finished("FAILED", code.name());
      }
    }
  }

  /**
   * Renews the lease and enforces the two reasons to stop mid-window.
   *
   * <p>The byte total is the job's tally when this window was claimed plus what
   * this window has produced. Under concurrency that under-counts what other
   * windows are writing right now, so the cap is approached rather than
   * enforced to the byte - which is the right trade: the alternative is a
   * shared counter updated per entry.
   */
  private void checkStillAllowedToRun(ClaimedWindow window, long windowBytes) {
    if (jobs.isCancelRequested(window.jobId())) {
      throw new ExportCancelledException();
    }
    long total = window.jobBytesWritten() + windowBytes;
    if (window.byteLimit() > 0 && total > window.byteLimit()) {
      throw new ByteLimitExceededException(total, window.byteLimit());
    }
    jobs.heartbeat(window.jobId(), window.index(), owner, lease);
  }

  private static java.security.MessageDigest sha256() {
    try {
      return java.security.MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      // SHA-256 is required of every Java platform; if it is missing, nothing
      // about this process is trustworthy.
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static final class Counters {
    private long entries;
    private long bytes;
    private String sha256;
    private long crc32;
  }
}
