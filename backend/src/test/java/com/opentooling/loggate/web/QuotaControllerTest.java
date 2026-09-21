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
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.namespaces.NamespaceInfo;
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

  @MockitoBean private NamespaceAuthorizer authorizer;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;

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
    when(authorizer.visibleTo(any()))
        .thenReturn(List.of(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev")));
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
                List.of(new QuotaReport.TeamBudget("platform", 100, 500))));

    mvc.perform(get("/api/quota").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxRangeSeconds").value(172800))
        .andExpect(jsonPath("$.yourActiveExports").value(1))
        .andExpect(jsonPath("$.activeExports").value(3))
        .andExpect(jsonPath("$.retentionSeconds").value(172800))
        .andExpect(jsonPath("$.teams[0].team").value("platform"))
        .andExpect(jsonPath("$.teams[0].usedBytes").value(100));
  }

  @Test
  void asksOnlyAboutTheCallersOwnTeams() throws Exception {
    // A budget is a team's operational business, so the report never reaches
    // beyond the teams the caller is entitled to see.
    when(authorizer.visibleTo(any()))
        .thenReturn(
            List.of(
                new NamespaceInfo("platform-dev", "platform", "ad-platform-dev"),
                new NamespaceInfo("platform-test", "platform", "ad-platform-dev")));
    when(quotas.report(any(), any()))
        .thenReturn(new QuotaReport(0, 0, 0, 0, 0, 0, 0, 0, List.of()));

    mvc.perform(get("/api/quota").with(alice())).andExpect(status().isOk());

    verify(quotas).report("alice-subject", List.of("platform"));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/quota")).andExpect(status().isUnauthorized());
  }
}
