package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.quota.BudgetHolder;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.quota.QuotaReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QuotaController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class QuotaControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;
  @MockitoBean private com.opentooling.loggate.pods.PodSource podSource;
  @MockitoBean private com.opentooling.loggate.activity.ActivityRepository activityRepository;
  @MockitoBean private com.opentooling.loggate.audit.AuditService auditService;
  @MockitoBean private com.opentooling.loggate.audit.AuditLog auditLog;

  private static OidcLoginRequestPostProcessor alice() {
    return oidcLogin()
        .idToken(
            token ->
                token
                    .subject("alice-subject")
                    .claim("preferred_username", "alice")
                    .claim("groups", List.of("ad-platform-dev")));
  }

  @Test
  void reportsTheLimitsAndWhatIsLeftOfThem() throws Exception {
    when(access.budgets(any())).thenReturn(List.of(BudgetHolder.team("platform")));
    when(quotas.report(eq("alice-subject"), any()))
        .thenReturn(
            new QuotaReport(
                172800,
                50L * 1024 * 1024 * 1024,
                2,
                1,
                10,
                3,
                86400,
                172800,
                List.of(new QuotaReport.Budget("platform", "platform", 100, 500))));

    mvc.perform(get("/api/quota").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxRangeSeconds").value(172800))
        .andExpect(jsonPath("$.yourActiveExports").value(1))
        .andExpect(jsonPath("$.activeExports").value(3))
        .andExpect(jsonPath("$.retentionSeconds").value(172800))
        .andExpect(jsonPath("$.budgets[0].label").value("platform"))
        .andExpect(jsonPath("$.budgets[0].usedBytes").value(100));
  }

  @Test
  void reportsOnlyTheBudgetsTheCallerSpendsFrom() throws Exception {
    // A budget is its holder's operational business, so the report asks the
    // access mode whose budgets these are rather than listing everyone's.
    when(access.budgets(any())).thenReturn(List.of(BudgetHolder.team("platform")));
    when(quotas.report(any(), any()))
        .thenReturn(new QuotaReport(0, 0, 0, 0, 0, 0, 0, 0, List.of()));

    mvc.perform(get("/api/quota").with(alice())).andExpect(status().isOk());

    verify(quotas).report("alice-subject", List.of(BudgetHolder.team("platform")));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/quota")).andExpect(status().isUnauthorized());
  }
}
