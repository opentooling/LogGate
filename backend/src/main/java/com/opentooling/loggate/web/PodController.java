package com.opentooling.loggate.web;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.pods.MetricsException;
import com.opentooling.loggate.pods.PodListing;
import com.opentooling.loggate.pods.PodSource;
import com.opentooling.loggate.security.AuthenticatedUser;
import com.opentooling.loggate.web.ExportController.ApiError;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The pods that ran in the chosen namespaces over the chosen range.
 *
 * <p>Asked only once namespaces are chosen, and only for a range an export
 * could cover, so the listing is never wider than the export it is for. The
 * namespaces are authorized exactly as an export of them would be, refusals
 * audited, because a pod list is a view into a namespace.
 */
@RestController
@RequestMapping("/api/pods")
public class PodController {

  /** As many namespaces as one export may name. */
  private static final int MAX_NAMESPACES = 50;

  private final PodSource pods;
  private final NamespaceAccess access;
  private final AuthorizationGate authorization;
  private final Duration maxRange;

  public PodController(
      PodSource pods, NamespaceAccess access, AuthorizationGate authorization, Duration maxRange) {
    this.pods = pods;
    this.access = access;
    this.authorization = authorization;
    this.maxRange = maxRange;
  }

  @GetMapping
  public ResponseEntity<?> list(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(name = "namespace", required = false) List<String> namespaces,
      @RequestParam(name = "cluster", required = false) List<String> clusters,
      @RequestParam(name = "from") String fromText,
      @RequestParam(name = "to") String toText,
      HttpServletRequest httpRequest) {
    if (!pods.enabled()) {
      return ResponseEntity.ok(PodListing.unavailable());
    }
    List<String> chosen = namespaces == null ? List.of() : namespaces;
    List<String> inClusters = clusters == null ? List.of() : clusters;
    if (chosen.isEmpty() || chosen.size() > MAX_NAMESPACES) {
      return ResponseEntity.badRequest()
          .body(new ApiError("choose between 1 and " + MAX_NAMESPACES + " namespaces to list pods"));
    }
    Instant from;
    Instant to;
    try {
      from = Instant.parse(fromText);
      to = Instant.parse(toText);
    } catch (DateTimeParseException e) {
      return ResponseEntity.badRequest().body(new ApiError("from and to must be ISO-8601 instants"));
    }
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(maxRange) > 0) {
      return ResponseEntity.badRequest()
          .body(new ApiError("the range must end after it starts and be no longer than an export"));
    }

    AuthenticatedUser user = AuthenticatedUser.from(principal);
    AccessDecision decision =
        authorization.check(user, inClusters, chosen, ClientAddress.of(httpRequest));
    if (!decision.isFullyAllowed()) {
      return ResponseEntity.status(403).body(decision);
    }
    // Scoped as an export would be, so team-label mode lists its own cluster's
    // pods only, whatever clusters were named.
    ExportRequest scoped =
        access.scope(new ExportRequest(chosen, null, null, null, from, to, inClusters));
    try {
      return ResponseEntity.ok(pods.list(scoped.clusters(), scoped.namespaces(), from, to));
    } catch (MetricsException e) {
      return ResponseEntity.status(503)
          .body(new ApiError("Pods could not be listed just now. Narrow by pod pattern instead."));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(new ApiError(e.getMessage()));
    }
  }
}
