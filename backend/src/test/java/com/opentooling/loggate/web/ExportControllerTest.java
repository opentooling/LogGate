package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.DenialReason;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportEstimator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExportController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class ExportControllerTest {

  private static final String VALID_BODY =
      """
      {"namespaces":["platform-dev"],"podPattern":"api-*",
       "from":"2026-09-20T00:00:00Z","to":"2026-09-21T00:00:00Z"}
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private ExportEstimator estimator;
  @MockitoBean private NamespaceAuthorizer authorizer;

  private static OidcLoginRequestPostProcessor alice() {
    return oidcLogin()
        .idToken(
            token ->
                token
                    .subject("alice-subject")
                    .claim("preferred_username", "alice")
                    .claim("groups", List.of("ad-platform-dev")));
  }

  private static void allowEverything(AuthorizationGate gate) {
    when(gate.check(any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of("platform-dev"), Map.of()));
  }

  @Test
  void returnsTheEstimateForAnAuthorizedRequest() throws Exception {
    allowEverything(authorization);
    when(estimator.estimate(any()))
        .thenReturn(
            new ExportEstimate(
                "{namespace=\"platform-dev\"}",
                Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"),
                44_040_192L,
                Map.of("platform-dev", 44_040_192L),
                900,
                96));

    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimatedBytes").value(44040192L))
        .andExpect(jsonPath("$.windowCount").value(96))
        .andExpect(jsonPath("$.bytesByNamespace['platform-dev']").value(44040192L));
  }

  @Test
  void refusesBeforeAskingLokiAboutNamespacesTheCallerCannotRead() throws Exception {
    // Sizing a namespace the caller may not read would leak its volume.
    when(authorization.check(any(), any(), any()))
        .thenReturn(
            new AccessDecision(Set.of(), Map.of("platform-dev", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.denied['platform-dev']").value("NOT_A_GROUP_MEMBER"));

    verify(estimator, never()).estimate(any());
  }

  @Test
  void rejectsARangeThatDoesNotMoveForward() throws Exception {
    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"namespaces":["platform-dev"],
                     "from":"2026-09-21T00:00:00Z","to":"2026-09-20T00:00:00Z"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("the export range must end after it starts"));

    verify(authorization, never()).check(any(), any(), any());
  }

  @Test
  void rejectsARequestWithNoNamespaces() throws Exception {
    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[],\"from\":\"2026-09-20T00:00:00Z\",\"to\":\"2026-09-21T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reportsAPlanItCannotBuildAsTheCallersProblem() throws Exception {
    allowEverything(authorization);
    when(estimator.estimate(any()))
        .thenThrow(new IllegalArgumentException("export would need 90000 windows"));

    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("export would need 90000 windows"));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(
            post("/api/exports/estimate")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isUnauthorized());
  }
}
