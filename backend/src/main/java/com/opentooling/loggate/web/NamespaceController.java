package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class NamespaceController {

  private final NamespaceAccess access;
  private final AuthorizationGate authorization;

  public NamespaceController(NamespaceAccess access, AuthorizationGate authorization) {
    this.access = access;
    this.authorization = authorization;
  }

  /**
   * The caller, and what they may choose from.
   *
   * <p>The mode is included so the page can explain itself: "namespaces your
   * team owns" and "every namespace in Loki" call for different words, and an
   * empty namespace list means something different in each.
   */
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal OidcUser principal) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    return new MeResponse(
        user.subject(),
        user.name(),
        user.groups(),
        access.mode(),
        !access.requiresNamespaces(),
        access.clusters(user),
        access.namespaces(user, List.of()),
        access.barrier(user));
  }

  /** Namespaces the caller may export, within the given clusters when any are named. */
  @GetMapping("/namespaces")
  public List<NamespaceInfo> namespaces(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(name = "cluster", required = false) List<String> clusters) {
    return access.namespaces(
        AuthenticatedUser.from(principal), clusters == null ? List.of() : clusters);
  }

  /**
   * Checks a specific set of namespaces, which is what export submission will
   * call. Returns 200 with the full decision when everything is allowed and 403
   * with the same body when anything is not, so a caller can see exactly which
   * namespace failed and why rather than guessing.
   */
  @PostMapping("/namespaces/authorize")
  public ResponseEntity<AccessDecision> authorize(
      @AuthenticationPrincipal OidcUser principal,
      @Valid @RequestBody AuthorizeRequest request,
      HttpServletRequest httpRequest) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    AccessDecision decision =
        authorization.check(
            user, request.clustersOrNone(), request.namespaces(), ClientAddress.of(httpRequest));
    if (!decision.isFullyAllowed()) {
      return ResponseEntity.status(403).body(decision);
    }
    return ResponseEntity.ok(decision);
  }

  /**
   * @param namespaces the namespaces to check
   * @param clusters the clusters to check, when there is a cluster dimension
   */
  public record AuthorizeRequest(
      @NotEmpty List<@NotEmpty String> namespaces, List<@NotEmpty String> clusters) {

    List<String> clustersOrNone() {
      return clusters == null ? List.of() : clusters;
    }
  }

  /**
   * @param subject stable OIDC subject
   * @param name display name
   * @param groups group claims as issued
   * @param mode how namespaces are granted
   * @param namespacesOptional whether an export may name no namespaces, meaning all
   * @param clusters clusters the caller may choose from; empty with no cluster dimension
   * @param namespaces namespaces the caller may export, across every cluster
   * @param barrier why the caller can export nothing, or null when they can
   */
  public record MeResponse(
      String subject,
      String name,
      Set<String> groups,
      LogGateProperties.AccessMode mode,
      boolean namespacesOptional,
      List<String> clusters,
      List<NamespaceInfo> namespaces,
      String barrier) {}
}
