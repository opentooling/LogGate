package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.delivery.DeliveryService;
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.JobState;
import com.opentooling.loggate.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/exports")
public class ExportController {

  private final AuthorizationGate authorization;
  private final ExportService exports;
  private final DeliveryService delivery;

  public ExportController(
      AuthorizationGate authorization, ExportService exports, DeliveryService delivery) {
    this.authorization = authorization;
    this.exports = exports;
    this.delivery = delivery;
  }

  /**
   * Sizes an export without running it.
   *
   * <p>Authorization first, then sizing: there is no reason to ask Loki about
   * namespaces the caller may not read, and doing so would leak their volume.
   *
   * <p>The answer carries the quota verdict as well as the size, so the cost
   * and the permission to pay it arrive together rather than the second one
   * arriving as a surprise after pressing start.
   */
  @PostMapping("/estimate")
  public ResponseEntity<?> estimate(
      @AuthenticationPrincipal OidcUser principal,
      @Valid @RequestBody ExportRequest request,
      HttpServletRequest httpRequest) {
    if (!request.hasValidRange()) {
      return ResponseEntity.badRequest()
          .body(new ApiError("the export range must end after it starts"));
    }

    AuthenticatedUser user = AuthenticatedUser.from(principal);
    AccessDecision decision =
        authorization.check(user, request.namespaces(), ClientAddress.of(httpRequest));
    if (!decision.isFullyAllowed()) {
      return ResponseEntity.status(403).body(decision);
    }

    try {
      ExportService.Preflight preflight = exports.preflight(user, request);
      return ResponseEntity.ok(EstimateResponse.of(preflight));
    } catch (IllegalArgumentException e) {
      // A request that cannot be planned, e.g. a range needing more windows
      // than the limit allows. That is the caller's to fix, not a server fault.
      return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }
  }

  /**
   * Submits an export.
   *
   * <p>Authorization, then sizing, then quota: each stage is cheaper than the
   * one after it, and each refusal is explainable on its own terms.
   */
  @PostMapping
  public ResponseEntity<?> submit(
      @AuthenticationPrincipal OidcUser principal,
      @Valid @RequestBody ExportRequest request,
      HttpServletRequest httpRequest) {
    if (!request.hasValidRange()) {
      return ResponseEntity.badRequest()
          .body(new ApiError("the export range must end after it starts"));
    }
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    String sourceIp = ClientAddress.of(httpRequest);

    AccessDecision access = authorization.check(user, request.namespaces(), sourceIp);
    if (!access.isFullyAllowed()) {
      return ResponseEntity.status(403).body(access);
    }

    try {
      ExportService.Submission submission = exports.submit(user, request, sourceIp);
      if (!submission.accepted()) {
        // 429 rather than 403: the request is legitimate, there is just no room
        // for it right now, and the caller should retry rather than change it.
        return ResponseEntity.status(429).body(new ApiError(submission.refusal()));
      }
      return ResponseEntity.status(201).body(submission.job());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }
  }

  /** The caller's exports, most recent first. */
  @GetMapping
  public List<ExportJob> list(@AuthenticationPrincipal OidcUser principal) {
    return exports.listFor(AuthenticatedUser.from(principal), 50);
  }

  /** One export's progress. */
  @GetMapping("/{id}")
  public ResponseEntity<?> get(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID id) {
    // Someone else's job is reported as missing rather than forbidden: whether
    // a job id exists is not the caller's business.
    return exports
        .findFor(AuthenticatedUser.from(principal), id)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(404).body(new ApiError("no such export")));
  }

  /** Asks an export to stop. Workers notice between pages. */
  @PostMapping("/{id}/cancel")
  public ResponseEntity<?> cancel(
      @AuthenticationPrincipal OidcUser principal,
      @PathVariable UUID id,
      HttpServletRequest httpRequest) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    boolean cancelled = exports.cancel(user, id, ClientAddress.of(httpRequest));
    return cancelled
        ? ResponseEntity.accepted().build()
        : ResponseEntity.status(404).body(new ApiError("no such export, or it already finished"));
  }

  /** The files of a finished export, each with a short-lived download URL. */
  @GetMapping("/{id}/downloads")
  public List<DeliveryService.Download> downloads(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID id) {
    return delivery.downloadsFor(requireDownloadableJob(principal, id));
  }

  /** A script that downloads every file and verifies it, for bulk retrieval. */
  @GetMapping(value = "/{id}/download.sh", produces = "text/x-shellscript")
  public ResponseEntity<String> downloadScript(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID id) {
    ExportJob job = requireDownloadableJob(principal, id);
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"loggate-" + id + "-download.sh\"")
        .body(delivery.downloadScript(job, delivery.downloadsFor(job)));
  }

  /**
   * The whole export as one archive, streamed.
   *
   * <p>Convenient, and deliberately the second-best option: this path carries
   * every byte through the control plane, which presigned URLs do not.
   */
  @GetMapping(value = "/{id}/archive.zip", produces = "application/zip")
  public ResponseEntity<StreamingResponseBody> archive(
      @AuthenticationPrincipal OidcUser principal, @PathVariable UUID id) {
    // The return type has to name StreamingResponseBody: with a wildcard,
    // Spring cannot tell this is a stream and tries to serialise it instead.
    ExportJob job = requireDownloadableJob(principal, id);
    StreamingResponseBody body = out -> delivery.streamArchive(job, out);
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=\"loggate-" + id + ".zip\"")
        .body(body);
  }

  /**
   * Resolves a job the caller owns that is actually downloadable.
   *
   * <p>Entitlement is re-checked here rather than trusted from submission
   * time, because an artifact must not outlive the access that produced it.
   */
  private ExportJob requireDownloadableJob(OidcUser principal, UUID id) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    ExportJob job =
        exports
            .findFor(user, id)
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "no such export"));
    if (job.state() != JobState.READY) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT,
          "this export is " + job.state() + ", so there is nothing to download");
    }
    AccessDecision access = authorization.check(user, job.namespaces(), "download");
    if (!access.isFullyAllowed()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "no longer entitled to these namespaces");
    }
    return job;
  }

  /**
   * A sizing, with the verdict quota would pass on it.
   *
   * @param estimate what the export would cost
   * @param admission whether it would be allowed, and why not when it would not
   */
  public record EstimateResponse(ExportEstimate estimate, Admission admission) {

    static EstimateResponse of(ExportService.Preflight preflight) {
      var decision = preflight.decision();
      return new EstimateResponse(
          preflight.estimate(),
          new Admission(decision.admitted(), decision.reason(), decision.byteLimit()));
    }

    /**
     * @param allowed whether quota would admit this export right now
     * @param reason why it would not, when it would not
     * @param byteLimit the cap it would run under, when it would be admitted
     */
    public record Admission(boolean allowed, String reason, long byteLimit) {}
  }

  /** @param message what went wrong */
  public record ApiError(String message) {}
}
