package com.opentooling.loggate.web;

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

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.security.AdminPolicy;
import com.opentooling.loggate.security.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

@RestController
@RequestMapping("/api")
public class NamespaceController {

  private final NamespaceAccess access;
  private final AuthorizationGate authorization;
  private final boolean podsListable;
  private final boolean podPatternAllowed;
  private final AdminPolicy admins;

  public NamespaceController(
      NamespaceAccess access,
      AuthorizationGate authorization,
      boolean podsListable,
      boolean podPatternAllowed,
      AdminPolicy admins) {
    this.access = access;
    this.authorization = authorization;
    this.podsListable = podsListable;
    this.podPatternAllowed = podPatternAllowed;
    this.admins = admins;
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
    List<String> clusters = access.clusters(user);
    return new MeResponse(
        user.subject(),
        user.name(),
        user.groups(),
        access.mode(),
        !access.requiresNamespaces(),
        clusters,
        choosesClusterFirst(clusters) ? List.of() : access.namespaces(user, List.of()),
        access.barrier(user),
        podsListable,
        podPatternAllowed,
        admins.isAdmin(user));
  }

  /**
   * Whether namespaces wait for a cluster to be chosen. In open mode they come
   * from Loki's index, and across every cluster that is the most expensive
   * question the page could ask, and for a list nobody reads: with twenty
   * clusters, the namespaces anyone wants are in the one or two they pick.
   * Team-label mode's come from this cluster's Kubernetes API, and cost Loki
   * nothing.
   */
  private boolean choosesClusterFirst(List<String> clusters) {
    return access.mode() == LogGateProperties.AccessMode.OPEN && !clusters.isEmpty();
  }

  /** Namespaces the caller may export, within the given clusters. */
  @GetMapping("/namespaces")
  public ResponseEntity<?> namespaces(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(name = "cluster", required = false) List<String> clusters) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    List<String> chosen = clusters == null ? List.of() : clusters;
    if (chosen.isEmpty() && choosesClusterFirst(access.clusters(user))) {
      throw new IllegalArgumentException("choose at least one cluster first");
    }
    return ResponseEntity.ok(access.namespaces(user, chosen));
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
   * @param namespaces namespaces the caller may export; empty when they wait
   *     for a cluster to be chosen, and are listed by {@code /api/namespaces}
   * @param barrier why the caller can export nothing, or null when they can
   * @param podsListable whether pods can be picked from a list
   * @param podPatternAllowed whether pods may be matched by pattern
   * @param admin whether the caller may see the activity and audit pages
   */
  public record MeResponse(
      String subject,
      String name,
      Set<String> groups,
      LogGateProperties.AccessMode mode,
      boolean namespacesOptional,
      List<String> clusters,
      List<NamespaceInfo> namespaces,
      String barrier,
      boolean podsListable,
      boolean podPatternAllowed,
      boolean admin) {}
}
