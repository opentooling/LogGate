package com.opentooling.loggate.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opentooling.loggate.audit.AuditLog;
import com.opentooling.loggate.security.AdminPolicy;
import com.opentooling.loggate.security.AuthenticatedUser;
import com.opentooling.loggate.web.ApiErrors.ApiError;

/**
 * The audit trail of downloads, for administrators: who took which export's
 * data, how, when and from where.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

  /** The largest page asked for at once. */
  static final int MAX_PAGE = 200;

  private final AuditLog log;
  private final AdminPolicy admins;

  public AuditController(AuditLog log, AdminPolicy admins) {
    this.log = log;
    this.admins = admins;
  }

  /** Why a non-administrator is refused, naming the role they would need. */
  static String notAdmin(AdminPolicy admins) {
    return admins.role().isEmpty()
        ? "no administrator role is configured here"
        : "this needs the \"" + admins.role() + "\" role on LogGate's client";
  }

  /**
   * @param limit how many to return, newest first
   * @param before the {@code next} of the previous page, to continue from it
   */
  @GetMapping("/downloads")
  public ResponseEntity<?> downloads(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "before", required = false) Long before) {
    if (!admins.isAdmin(AuthenticatedUser.from(principal))) {
      return ResponseEntity.status(403).body(new ApiError(notAdmin(admins)));
    }
    if (limit < 1 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }
    return ResponseEntity.ok(new DownloadsResponse(log.downloads(limit, before), log.totals()));
  }

  /**
   * @param page the downloads asked for
   * @param totals every download ever recorded, by how it was made
   */
  public record DownloadsResponse(
      AuditLog.Page page, java.util.Map<com.opentooling.loggate.audit.AuditAction, Long> totals) {}
}
