package com.opentooling.loggate.web;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.opentooling.loggate.activity.ActivityRepository;
import com.opentooling.loggate.security.AdminPolicy;
import com.opentooling.loggate.security.AuthenticatedUser;
import com.opentooling.loggate.web.ApiErrors.ApiError;

/**
 * The page's dashboard: how exporting has gone, installation-wide.
 *
 * <p>For administrators only. The report holds aggregates, but across
 * everyone's exports, which is an operator's view rather than a user's.
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

  /** The periods on offer, each with a bucket width that gives a readable chart. */
  static final Map<String, Duration[]> PERIODS =
      Map.of(
          "24h", new Duration[] {Duration.ofHours(24), Duration.ofHours(1)},
          "7d", new Duration[] {Duration.ofDays(7), Duration.ofHours(6)},
          "30d", new Duration[] {Duration.ofDays(30), Duration.ofDays(1)});

  private final ActivityRepository activity;
  private final AdminPolicy admins;

  public ActivityController(ActivityRepository activity, AdminPolicy admins) {
    this.activity = activity;
    this.admins = admins;
  }

  @GetMapping
  public ResponseEntity<?> report(
      @AuthenticationPrincipal OidcUser principal,
      @RequestParam(name = "period", defaultValue = "24h") String period) {
    if (!admins.isAdmin(AuthenticatedUser.from(principal))) {
      return ResponseEntity.status(403).body(new ApiError(AuditController.notAdmin(admins)));
    }
    Duration[] chosen = PERIODS.get(period);
    if (chosen == null) {
      throw new IllegalArgumentException("period must be 24h, 7d or 30d");
    }
    return ResponseEntity.ok(activity.report(chosen[0], chosen[1]));
  }
}
