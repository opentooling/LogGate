package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.DenialReason;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.opentooling.loggate.config.WebConfig;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NamespaceController.class)
// No component scanning, so the controller is imported like any other bean.
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class NamespaceControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private NamespaceAuthorizer authorizer;
  @MockitoBean private AuditService audit;

  /** A signed-in caller in the platform team. */
  private static OidcLoginRequestPostProcessor alice() {
    return oidcLogin()
        .idToken(
            token ->
                token
                    .subject("alice-subject")
                    .claim("preferred_username", "alice")
                    .claim("groups", List.of("/ad-platform-dev")));
  }

  @Test
  void meReturnsTheCallerAndTheirNamespaces() throws Exception {
    when(authorizer.visibleTo(any()))
        .thenReturn(List.of(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev")));

    mvc.perform(get("/api/me").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("alice"))
        .andExpect(jsonPath("$.subject").value("alice-subject"))
        .andExpect(jsonPath("$.namespaces[0].name").value("platform-dev"))
        .andExpect(jsonPath("$.namespaces[0].owningGroup").value("ad-platform-dev"));
  }

  @Test
  void namespacesListsWhatTheCallerMayExport() throws Exception {
    when(authorizer.visibleTo(any()))
        .thenReturn(List.of(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev")));

    mvc.perform(get("/api/namespaces").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].team").value("platform"));
  }

  @Test
  void authorizeReturnsOkAndAuditsNothingWhenEverythingIsAllowed() throws Exception {
    when(authorizer.authorize(any(), any()))
        .thenReturn(new AccessDecision(Set.of("platform-dev"), Map.of()));

    mvc.perform(
            post("/api/namespaces/authorize")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[\"platform-dev\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed[0]").value("platform-dev"));

    verify(audit, never()).record(anyString(), any(), any(), anyString());
  }

  @Test
  void authorizeReturnsForbiddenAndAuditsWhenAnythingIsRefused() throws Exception {
    when(authorizer.authorize(any(), any()))
        .thenReturn(
            new AccessDecision(
                Set.of(), Map.of("payments-dev", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(
            post("/api/namespaces/authorize")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[\"payments-dev\"]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.denied['payments-dev']").value("NOT_A_GROUP_MEMBER"));

    verify(audit)
        .record(eq("alice-subject"), eq(AuditAction.NAMESPACE_ACCESS_DENIED), any(), anyString());
  }

  @Test
  void authorizeRejectsAnEmptyRequest() throws Exception {
    mvc.perform(
            post("/api/namespaces/authorize")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void authorizeRejectsARequestWithoutACsrfToken() throws Exception {
    mvc.perform(
            post("/api/namespaces/authorize")
                .with(alice())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[\"platform-dev\"]}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithAnonymousUser
  void apiCallsWithoutASessionGet401RatherThanARedirect() throws Exception {
    // A fetch() cannot usefully follow a redirect to Keycloak, so the SPA needs
    // a status it can act on.
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }
}
