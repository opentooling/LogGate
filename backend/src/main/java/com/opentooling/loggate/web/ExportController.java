package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportEstimator;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exports")
public class ExportController {

  private final AuthorizationGate authorization;
  private final ExportEstimator estimator;

  public ExportController(AuthorizationGate authorization, ExportEstimator estimator) {
    this.authorization = authorization;
    this.estimator = estimator;
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

  /** @param message what went wrong */
  public record ApiError(String message) {}
}
