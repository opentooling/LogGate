package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.activity.ActivityReport;
import com.opentooling.loggate.activity.ActivityRepository;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.pods.PodSource;
import com.opentooling.loggate.quota.QuotaGuard;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ActivityController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class ActivityControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;
  @MockitoBean private PodSource pods;
  @MockitoBean private ActivityRepository activity;

  /** Someone holding the administrator role, as ClientRoleOidcUserService attaches it. */
  static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .OidcLoginRequestPostProcessor
      admin() {
    return oidcLogin()
        .authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                com.opentooling.loggate.security.AuthenticatedUser.CLIENT_ROLE_AUTHORITY
                    + "loggate-admin"));
  }
  @MockitoBean private com.opentooling.loggate.audit.AuditService auditService;
  @MockitoBean private com.opentooling.loggate.audit.AuditLog auditLog;

  private static ActivityReport report() {
    Instant at = Instant.parse("2026-09-20T00:00:00Z");
    return new ActivityReport(
        at,
        at.plus(Duration.ofHours(1)),
        3600,
        new ActivityReport.Now(1, 4, 2),
        new ActivityReport.Totals(3, 1, 2, 2, 1, 0, 1024, 10),
        Map.of("BYTE_LIMIT_EXCEEDED", 1L),
        new ActivityReport.Durations(12.0, 40.0, 41.0),
        List.of(new ActivityReport.Point(at, 3, 1, 2, 1, 0, 1024)));
  }

  @Test
  void reportsTheLastDayByTheHourByDefault() throws Exception {
    when(activity.report(Duration.ofHours(24), Duration.ofHours(1))).thenReturn(report());

    mvc.perform(get("/api/activity").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bucketSeconds").value(3600))
        .andExpect(jsonPath("$.now.windowsPending").value(4))
        .andExpect(jsonPath("$.totals.denied").value(2))
        .andExpect(jsonPath("$.failures.BYTE_LIMIT_EXCEEDED").value(1))
        .andExpect(jsonPath("$.durationSeconds.p95").value(40.0))
        .andExpect(jsonPath("$.series[0].bytesExported").value(1024));
  }

  @Test
  void offersAWeekAndAMonthInWiderBuckets() throws Exception {
    when(activity.report(any(), any())).thenReturn(report());
    mvc.perform(get("/api/activity?period=7d").with(admin())).andExpect(status().isOk());
    verify(activity).report(Duration.ofDays(7), Duration.ofHours(6));
    mvc.perform(get("/api/activity?period=30d").with(admin())).andExpect(status().isOk());
    verify(activity).report(Duration.ofDays(30), Duration.ofDays(1));
  }

  @Test
  void refusesAPeriodItDoesNotOffer() throws Exception {
    mvc.perform(get("/api/activity?period=1y").with(admin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("period must be 24h, 7d or 30d"));
  }

  @Test
  void isForAdministratorsOnly() throws Exception {
    // Able to export is not enough: the report spans everyone's exports.
    mvc.perform(get("/api/activity").with(oidcLogin()))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.message").value("this needs the \"loggate-admin\" role on LogGate's client"));
    verify(activity, never()).report(any(), any());
  }
}
