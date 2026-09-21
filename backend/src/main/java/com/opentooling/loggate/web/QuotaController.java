package com.opentooling.loggate.web;

import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.quota.QuotaReport;
import com.opentooling.loggate.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the caller is allowed to spend, and what is left of it. */
@RestController
@RequestMapping("/api/quota")
public class QuotaController {

  private final NamespaceAccess access;
  private final QuotaGuard quotas;

  public QuotaController(NamespaceAccess access, QuotaGuard quotas) {
    this.access = access;
    this.quotas = quotas;
  }

  /**
   * The limits, and what has been spent against them.
   *
   * <p>Only the caller's own budgets are reported: their teams, or in open mode
   * themselves. A budget is operational information about whoever holds it,
   * and someone else has no business reading how much they exported today.
   */
  @GetMapping
  public QuotaReport quota(@AuthenticationPrincipal OidcUser principal) {
    AuthenticatedUser user = AuthenticatedUser.from(principal);
    return quotas.report(user.subject(), access.budgets(user));
  }
}
