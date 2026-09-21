package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.quota.QuotaReport;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the caller is allowed to spend, and what is left of it. */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

  private final NamespaceAuthorizer authorizer;
  private final QuotaGuard quotas;

  public QuotaController(NamespaceAuthorizer authorizer, QuotaGuard quotas) {
    this.authorizer = authorizer;
    this.quotas = quotas;
  }

  /**
   * The limits, and what has been spent against them.
   *
   * <p>Only the caller's own teams are reported: a budget is operational
   * information about a team, and someone outside it has no business reading
   * how much that team has exported today.
   */
  @GetMapping
  public QuotaReport quota(@AuthenticationPrincipal OidcUser principal) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    List<String> teams =
        authorizer.visibleTo(user.groups()).stream().map(NamespaceInfo::team).distinct().toList();
    return quotas.report(user.subject(), teams);
  }
}
