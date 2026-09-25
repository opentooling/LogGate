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

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.DenialReason;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.SecurityConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.opentooling.loggate.quota.QuotaGuard;
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

  @MockitoBean private com.opentooling.loggate.authz.NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;
  @MockitoBean private com.opentooling.loggate.pods.PodSource podSource;
  @MockitoBean private com.opentooling.loggate.activity.ActivityRepository activityRepository;
  @MockitoBean private com.opentooling.loggate.audit.AuditService auditService;
  @MockitoBean private com.opentooling.loggate.audit.AuditLog auditLog;

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
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.TEAM_LABEL);
    when(access.requiresNamespaces()).thenReturn(true);
    when(access.namespaces(any(), any()))
        .thenReturn(List.of(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev")));

    mvc.perform(get("/api/me").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("alice"))
        .andExpect(jsonPath("$.subject").value("alice-subject"))
        .andExpect(jsonPath("$.mode").value("TEAM_LABEL"))
        .andExpect(jsonPath("$.namespacesOptional").value(false))
        // Patterns are allowed unless switched off.
        .andExpect(jsonPath("$.podPatternAllowed").value(true))
        .andExpect(jsonPath("$.admin").value(false))
        .andExpect(jsonPath("$.namespaces[0].name").value("platform-dev"))
        .andExpect(jsonPath("$.namespaces[0].owningGroup").value("ad-platform-dev"));
  }

  @Test
  void meSaysWhatTheCallerIsMissingWhenTheyCanExportNothing() throws Exception {
    // The page explains the barrier in the access mode's own words: a group to
    // join in team-label mode, a role to be granted in open mode.
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.OPEN);
    when(access.clusters(any())).thenReturn(List.of("edge-eu", "core-us"));
    when(access.barrier(any())).thenReturn("Exporting logs here needs the \"export-logs\" role");

    mvc.perform(get("/api/me").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("OPEN"))
        .andExpect(jsonPath("$.namespacesOptional").value(true))
        .andExpect(jsonPath("$.clusters[1]").value("core-us"))
        .andExpect(jsonPath("$.barrier").value("Exporting logs here needs the \"export-logs\" role"));
  }

  @Test
  void meLeavesNamespacesUntilAClusterIsChosenInOpenMode() throws Exception {
    // Every namespace in every cluster is the costliest thing Loki could be
    // asked on page load, for a list nobody reads before picking a cluster.
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.OPEN);
    when(access.clusters(any())).thenReturn(List.of("edge-eu", "core-us"));

    mvc.perform(get("/api/me").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.namespaces").isEmpty())
        .andExpect(jsonPath("$.podsListable").value(false));
    verify(access, never()).namespaces(any(), any());
  }

  @Test
  void meListsNamespacesInOpenModeWhenThereAreNoClustersToChoose() throws Exception {
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.OPEN);
    when(access.clusters(any())).thenReturn(List.of());
    when(access.namespaces(any(), any())).thenReturn(List.of(new NamespaceInfo("a", null, null)));

    mvc.perform(get("/api/me").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.namespaces[0].name").value("a"));
  }

  @Test
  void meSaysWhetherTheCallerIsAnAdministrator() throws Exception {
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.TEAM_LABEL);
    mvc.perform(get("/api/me").with(ActivityControllerTest.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admin").value(true));
  }

  @Test
  void namespacesWaitForAClusterInOpenMode() throws Exception {
    when(access.mode()).thenReturn(LogGateProperties.AccessMode.OPEN);
    when(access.clusters(any())).thenReturn(List.of("edge-eu"));

    mvc.perform(get("/api/namespaces").with(alice()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("choose at least one cluster first"));
    verify(access, never()).namespaces(any(), any());
  }

  @Test
  void namespacesListsWhatTheCallerMayExport() throws Exception {
    when(access.namespaces(any(), any()))
        .thenReturn(List.of(new NamespaceInfo("platform-dev", "platform", "ad-platform-dev")));

    mvc.perform(get("/api/namespaces").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].team").value("platform"));
    verify(access).namespaces(any(), eq(List.of()));
  }

  @Test
  void namespacesAreNarrowedToTheClustersAskedFor() throws Exception {
    when(access.namespaces(any(), any()))
        .thenReturn(List.of(new NamespaceInfo("checkout-prod", null, null)));

    mvc.perform(get("/api/namespaces?cluster=edge-eu&cluster=core-us").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("checkout-prod"));
    verify(access).namespaces(any(), eq(List.of("edge-eu", "core-us")));
  }

  @Test
  void authorizePassesClustersThroughWhenGiven() throws Exception {
    when(authorization.check(any(), any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of("checkout-prod"), Map.of()));

    mvc.perform(
            post("/api/namespaces/authorize")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[\"checkout-prod\"],\"clusters\":[\"edge-eu\"]}"))
        .andExpect(status().isOk());
    verify(authorization)
        .check(any(), eq(List.of("edge-eu")), eq(List.of("checkout-prod")), anyString());
  }

  @Test
  void authorizeReturnsOkWhenEverythingIsAllowed() throws Exception {
    when(authorization.check(any(), any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of("platform-dev"), Map.of()));

    mvc.perform(
            post("/api/namespaces/authorize")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[\"platform-dev\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allowed[0]").value("platform-dev"));
  }

  @Test
  void authorizeReturnsForbiddenWhenAnythingIsRefused() throws Exception {
    when(authorization.check(any(), any(), any(), any()))
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

    // Auditing lives in AuthorizationGate, so the controller only has to pass
    // the caller and their address through.
    verify(authorization).check(any(), eq(List.of()), eq(List.of("payments-dev")), anyString());
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
