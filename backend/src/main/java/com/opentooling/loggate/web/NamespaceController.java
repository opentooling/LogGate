package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class NamespaceController {

  private final NamespaceAuthorizer authorizer;
  private final AuthorizationGate authorization;

  public NamespaceController(NamespaceAuthorizer authorizer, AuthorizationGate authorization) {
    this.authorizer = authorizer;
    this.authorization = authorization;
  }

  /** The caller, and what they are entitled to. */
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal OidcUser principal) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    return new MeResponse(
        user.subject(), user.name(), user.groups(), authorizer.visibleTo(user.groups()));
  }

  /** Namespaces the caller may export from. */
  @GetMapping("/namespaces")
  public List<NamespaceInfo> namespaces(@AuthenticationPrincipal OidcUser principal) {
    return authorizer.visibleTo(AuthenticatedUser.from(principal).groups());
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
        authorization.check(user, request.namespaces(), ClientAddress.of(httpRequest));
    if (!decision.isFullyAllowed()) {
      return ResponseEntity.status(403).body(decision);
    }
    return ResponseEntity.ok(decision);
  }

  /** @param namespaces the namespaces to check */
  public record AuthorizeRequest(@NotEmpty List<@NotEmpty String> namespaces) {}

  /**
   * @param subject stable OIDC subject
   * @param name display name
   * @param groups group claims as issued
   * @param namespaces namespaces the caller may export
   */
  public record MeResponse(
      String subject, String name, Set<String> groups, List<NamespaceInfo> namespaces) {}
}
