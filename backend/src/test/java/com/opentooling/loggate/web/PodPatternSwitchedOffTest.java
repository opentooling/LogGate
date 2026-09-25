package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.DenialReason;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.quota.QuotaGuard;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** With loggate.pods.allow-pattern off, pods are named or not narrowed at all. */
@WebMvcTest(
    controllers = {ExportController.class, NamespaceController.class},
    properties = "loggate.pods.allow-pattern=false")
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class PodPatternSwitchedOffTest {

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

  private static String body(String pods) {
    return """
        {"namespaces":["platform-dev"], %s
         "from":"2026-09-20T00:00:00Z","to":"2026-09-20T01:00:00Z"}
        """
        .formatted(pods);
  }

  @Test
  void thePageIsToldSoItOffersNoPatternField() throws Exception {
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.TEAM_LABEL);
    mvc.perform(get("/api/me").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.podPatternAllowed").value(false));
  }

  @Test
  void aRequestCarryingAPatternIsRefusedWhateverThePageShowed() throws Exception {
    for (String path : List.of("/api/exports/estimate", "/api/exports")) {
      mvc.perform(
              post(path)
                  .with(oidcLogin())
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("\"podPattern\":\"api-*\",")))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message")
                  .value("pod patterns are switched off here: pick pods from the list"));
    }
    verify(authorization, never()).check(any(), any(), any(), any());
  }

  @Test
  void pickedPodsAndAnEmptyPatternStillGetThrough() throws Exception {
    // Past validation to authorization, which here refuses: that is the proof
    // the request was not stopped for its pods.
    when(authorization.check(any(), any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of(), Map.of("platform-dev", DenialReason.NOT_A_GROUP_MEMBER)));
    for (String pods : List.of("\"pods\":[\"api-1\"],", "\"podPattern\":\" \",", "")) {
      mvc.perform(
              post("/api/exports/estimate")
                  .with(oidcLogin())
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body(pods)))
          .andExpect(status().isForbidden());
    }
  }
}
