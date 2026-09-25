package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AccessDecision;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.DenialReason;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.export.ExportRequest;
import com.opentooling.loggate.pods.MetricsException;
import com.opentooling.loggate.pods.PodInfo;
import com.opentooling.loggate.pods.PodListing;
import com.opentooling.loggate.pods.PodSource;
import com.opentooling.loggate.quota.QuotaGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PodController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class PodControllerTest {

  private static final String RANGE = "&from=2026-09-20T00:00:00Z&to=2026-09-20T06:00:00Z";
  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-20T06:00:00Z");

  @Autowired private MockMvc mvc;

  @MockitoBean private NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;
  @MockitoBean private PodSource pods;
  @MockitoBean private com.opentooling.loggate.activity.ActivityRepository activityRepository;
  @MockitoBean private com.opentooling.loggate.audit.AuditService auditService;
  @MockitoBean private com.opentooling.loggate.audit.AuditLog auditLog;

  private static OidcLoginRequestPostProcessor alice() {
    return oidcLogin()
        .idToken(token -> token.subject("alice-subject").claim("preferred_username", "alice"));
  }

  private void allowEverything() {
    when(pods.enabled()).thenReturn(true);
    when(authorization.check(any(), any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of("platform-dev"), Map.of()));
    when(access.scope(any())).thenAnswer(call -> call.getArgument(0));
  }

  @Test
  void saysPodsCannotBeListedWhenNoSourceIsConfigured() throws Exception {
    mvc.perform(get("/api/pods?namespace=platform-dev" + RANGE).with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
    verify(authorization, never()).check(any(), any(), any(), any());
  }

  @Test
  void listsThePodsOfAuthorizedNamespacesAsAnExportWouldBeScoped() throws Exception {
    allowEverything();
    // Team-label mode pins the cluster whatever was asked for.
    when(access.scope(any()))
        .thenAnswer(
            call -> ((ExportRequest) call.getArgument(0)).withClusters(List.of("k3d-loggate")));
    when(pods.list(eq(List.of("k3d-loggate")), eq(List.of("platform-dev")), eq(FROM), eq(TO)))
        .thenReturn(new PodListing(true, List.of(new PodInfo("platform-dev", "api-1")), false));

    mvc.perform(get("/api/pods?namespace=platform-dev&cluster=elsewhere" + RANGE).with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.pods[0].namespace").value("platform-dev"))
        .andExpect(jsonPath("$.pods[0].name").value("api-1"));
    verify(authorization)
        .check(any(), eq(List.of("elsewhere")), eq(List.of("platform-dev")), anyString());
  }

  @Test
  void refusesNamespacesTheCallerMayNotExport() throws Exception {
    allowEverything();
    when(authorization.check(any(), any(), any(), any()))
        .thenReturn(
            new AccessDecision(Set.of(), Map.of("payments-dev", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(get("/api/pods?namespace=payments-dev" + RANGE).with(alice()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.denied['payments-dev']").value("NOT_A_GROUP_MEMBER"));
    verify(pods, never()).list(any(), any(), any(), any());
  }

  @Test
  void listsForChosenNamespacesOnly() throws Exception {
    allowEverything();
    mvc.perform(get("/api/pods?" + RANGE.substring(1)).with(alice()))
        .andExpect(status().isBadRequest());
    String tooMany =
        IntStream.rangeClosed(1, 51).mapToObj(i -> "namespace=ns-" + i).collect(Collectors.joining("&"));
    mvc.perform(get("/api/pods?" + tooMany + RANGE).with(alice()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("choose between 1 and 50 namespaces to list pods"));
  }

  @Test
  void listsOverARangeAnExportCouldCover() throws Exception {
    allowEverything();
    mvc.perform(get("/api/pods?namespace=a&from=yesterday&to=today").with(alice()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("from and to must be ISO-8601 instants"));
    mvc.perform(
            get("/api/pods?namespace=a&from=2026-09-20T06:00:00Z&to=2026-09-20T00:00:00Z")
                .with(alice()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/pods?namespace=a&from=2026-09-10T00:00:00Z&to=2026-09-20T00:00:00Z")
                .with(alice()))
        .andExpect(status().isBadRequest());
    verify(pods, never()).list(any(), any(), any(), any());
  }

  @Test
  void saysSoWhenTheMetricsEndpointFails() throws Exception {
    allowEverything();
    when(pods.list(any(), any(), any(), any())).thenThrow(new MetricsException("down"));
    mvc.perform(get("/api/pods?namespace=platform-dev" + RANGE).with(alice()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            jsonPath("$.message")
                .value("Pods could not be listed just now. Narrow by pod pattern instead."));
  }

  @Test
  void refusesANamespaceTheSourceCannotQuery() throws Exception {
    allowEverything();
    when(pods.list(any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("not a valid namespace name: X"));
    mvc.perform(get("/api/pods?namespace=X" + RANGE).with(alice()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("not a valid namespace name: X"));
  }
}
