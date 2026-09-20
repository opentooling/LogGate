package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportEstimator;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.jobs.ExportJob;
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

@RestController
@RequestMapping("/api/exports")
public class ExportController {

  private final AuthorizationGate authorization;
  private final ExportEstimator estimator;
  private final ExportService exports;

  public ExportController(
      AuthorizationGate authorization, ExportEstimator estimator, ExportService exports) {
    this.authorization = authorization;
    this.estimator = estimator;
    this.exports = exports;
  }

  /**
   * Sizes an export without running it.
   *
   * <p>Authorization first, then sizing: there is no reason to ask Loki about
   * namespaces the caller may not read, and doing so would leak their volume.
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
      ExportEstimate estimate = estimator.estimate(request);
      return ResponseEntity.ok(estimate);
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

  /** @param message what went wrong */
  public record ApiError(String message) {}
}
