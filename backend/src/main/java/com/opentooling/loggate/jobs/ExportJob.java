package com.opentooling.loggate.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An export as the API reports it.
 *
 * @param id job identifier
 * @param requestedBy OIDC subject of the caller
 * @param state where the job is in its life
 * @param failureCode why it stopped, when it failed
 * @param failureDetail human-readable detail for the failure
 * @param namespaces namespaces being exported
 * @param selector the generated LogQL selector
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 * @param estimatedBytes what sizing predicted
 * @param byteLimit the cap this job was admitted under
 * @param windowsTotal how many windows the plan has
 * @param windowsDone how many have finished
 * @param bytesWritten uncompressed bytes extracted so far
 * @param entriesWritten entries extracted so far
 * @param cancelRequested whether a cancellation has been asked for
 * @param createdAt when it was submitted
 * @param finishedAt when it stopped, if it has
 * @param expiresAt when its files are deleted, once it is ready
 * @param clusters clusters it was taken from; empty with no cluster dimension
 * @param format what its files hold
 */
public record ExportJob(
    UUID id,
    String requestedBy,
    JobState state,
    String failureCode,
    String failureDetail,
    List<String> namespaces,
    String selector,
    Instant from,
    Instant to,
    long estimatedBytes,
    long byteLimit,
    int windowsTotal,
    int windowsDone,
    long bytesWritten,
    long entriesWritten,
    boolean cancelRequested,
    Instant createdAt,
    Instant finishedAt,
    Instant expiresAt,
    List<String> clusters,
    com.opentooling.loggate.export.OutputFormat format) {

  /** An export in the default format, as every export was before there was a choice. */
  public ExportJob(
      UUID id,
      String requestedBy,
      JobState state,
      String failureCode,
      String failureDetail,
      List<String> namespaces,
      String selector,
      Instant from,
      Instant to,
      long estimatedBytes,
      long byteLimit,
      int windowsTotal,
      int windowsDone,
      long bytesWritten,
      long entriesWritten,
      boolean cancelRequested,
      Instant createdAt,
      Instant finishedAt,
      Instant expiresAt,
      List<String> clusters) {
    this(id, requestedBy, state, failureCode, failureDetail, namespaces, selector, from, to,
        estimatedBytes, byteLimit, windowsTotal, windowsDone, bytesWritten, entriesWritten,
        cancelRequested, createdAt, finishedAt, expiresAt, clusters,
        com.opentooling.loggate.export.OutputFormat.JSON);
  }

  /**
   * Progress as a fraction, for a progress bar that means something.
   *
   * <p>Annotated because it is derived rather than a record component, and
   * Jackson would otherwise leave it out of the API response.
   */
  @com.fasterxml.jackson.annotation.JsonProperty("progress")
  public double progress() {
    return windowsTotal == 0 ? 0 : (double) windowsDone / windowsTotal;
  }
}
