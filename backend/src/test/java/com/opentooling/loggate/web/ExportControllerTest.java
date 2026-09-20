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
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.JobState;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
  @MockitoBean private ExportService exports;
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

  private static ExportJob job(java.util.UUID id, JobState state) {
    return new ExportJob(
        id,
        "alice-subject",
        state,
        null,
        null,
        List.of("platform-dev"),
        "{namespace=\"platform-dev\"}",
        Instant.parse("2026-09-20T00:00:00Z"),
        Instant.parse("2026-09-21T00:00:00Z"),
        1024,
        2048,
        8,
        2,
        512,
        5,
        false,
        Instant.parse("2026-09-20T09:00:00Z"),
        null);
  }

  @Test
  void queuesAnAdmittedExport() throws Exception {
    allowEverything(authorization);
    var id = java.util.UUID.randomUUID();
    when(exports.submit(any(), any(), any()))
        .thenReturn(new ExportService.Submission(job(id, JobState.PLANNED), null, null));

    mvc.perform(
            post("/api/exports")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.state").value("PLANNED"))
        .andExpect(jsonPath("$.windowsTotal").value(8));
  }

  @Test
  void refusesAnExportThatIsOverQuotaWithTooManyRequests() throws Exception {
    // 429 rather than 403: the request is legitimate, there is simply no room
    // for it right now, so the caller should retry rather than change it.
    allowEverything(authorization);
    when(exports.submit(any(), any(), any()))
        .thenReturn(new ExportService.Submission(null, "you already have 2 exports running", null));

    mvc.perform(
            post("/api/exports")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.message").value("you already have 2 exports running"));
  }

  @Test
  void refusesToSubmitForANamespaceTheCallerCannotRead() throws Exception {
    when(authorization.check(any(), any(), any()))
        .thenReturn(
            new AccessDecision(Set.of(), Map.of("platform-dev", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(
            post("/api/exports")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isForbidden());

    verify(exports, never()).submit(any(), any(), any());
  }

  @Test
  void rejectsASubmissionWithAReversedRange() throws Exception {
    mvc.perform(
            post("/api/exports")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"namespaces":["platform-dev"],
                     "from":"2026-09-21T00:00:00Z","to":"2026-09-20T00:00:00Z"}
                    """))
        .andExpect(status().isBadRequest());

    verify(exports, never()).submit(any(), any(), any());
  }

  @Test
  void reportsAPlanThatCannotBeBuiltAsTheCallersProblemOnSubmit() throws Exception {
    allowEverything(authorization);
    when(exports.submit(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("export would need 90000 windows"));

    mvc.perform(
            post("/api/exports")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("export would need 90000 windows"));
  }

  @Test
  void listsTheCallersExports() throws Exception {
    when(exports.listFor(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(job(java.util.UUID.randomUUID(), JobState.READY)));

    mvc.perform(get("/api/exports").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].state").value("READY"));
  }

  @Test
  void reportsProgressForOneExport() throws Exception {
    var id = java.util.UUID.randomUUID();
    when(exports.findFor(any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(java.util.Optional.of(job(id, JobState.RUNNING)));

    mvc.perform(get("/api/exports/" + id).with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowsDone").value(2))
        .andExpect(jsonPath("$.progress").value(0.25));
  }

  @Test
  void reportsSomeoneElsesExportAsMissingRatherThanForbidden() throws Exception {
    // Whether a job id exists is not the caller's business.
    when(exports.findFor(any(), any())).thenReturn(java.util.Optional.empty());

    mvc.perform(get("/api/exports/" + java.util.UUID.randomUUID()).with(alice()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("no such export"));
  }

  @Test
  void acceptsACancellation() throws Exception {
    when(exports.cancel(any(), any(), any())).thenReturn(true);

    mvc.perform(post("/api/exports/" + java.util.UUID.randomUUID() + "/cancel").with(alice()).with(csrf()))
        .andExpect(status().isAccepted());
  }

  @Test
  void reportsCancellingSomethingThatIsNotRunningAsMissing() throws Exception {
    when(exports.cancel(any(), any(), any())).thenReturn(false);

    mvc.perform(post("/api/exports/" + java.util.UUID.randomUUID() + "/cancel").with(alice()).with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  void listsTheFilesOfAFinishedExport() throws Exception {
    var id = java.util.UUID.randomUUID();
    when(exports.findFor(any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(java.util.Optional.of(job(id, JobState.READY)));
    allowEverything(authorization);
    when(delivery.downloadsFor(any()))
        .thenReturn(
            List.of(
                new com.opentooling.loggate.delivery.DeliveryService.Download(
                    "manifest.json", "jobs/x/manifest.json", 12, "abc", "https://storage/x")));

    mvc.perform(get("/api/exports/" + id + "/downloads").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("manifest.json"))
        .andExpect(jsonPath("$[0].url").value("https://storage/x"));
  }

  @Test
  void refusesToDownloadAnExportThatIsNotFinished() throws Exception {
    var id = java.util.UUID.randomUUID();
    when(exports.findFor(any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(java.util.Optional.of(job(id, JobState.RUNNING)));

    mvc.perform(get("/api/exports/" + id + "/downloads").with(alice()))
        .andExpect(status().isConflict());
  }

  @Test
  void recheckEntitlementWhenAnArtifactIsRequested() throws Exception {
    // An artifact must not outlive the access that produced it, so the check
    // happens at download time and not only at submission.
    var id = java.util.UUID.randomUUID();
    when(exports.findFor(any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(java.util.Optional.of(job(id, JobState.READY)));
    when(authorization.check(any(), any(), any()))
        .thenReturn(
            new AccessDecision(Set.of(), Map.of("platform-dev", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(get("/api/exports/" + id + "/downloads").with(alice()))
        .andExpect(status().isForbidden());

    verify(delivery, never()).downloadsFor(any());
  }

  @Test
  void reportsDownloadsForSomeoneElsesExportAsMissing() throws Exception {
    when(exports.findFor(any(), any())).thenReturn(java.util.Optional.empty());

    mvc.perform(get("/api/exports/" + java.util.UUID.randomUUID() + "/downloads").with(alice()))
        .andExpect(status().isNotFound());
  }

  @Test
  void servesADownloadScriptAsAnAttachment() throws Exception {
    var id = java.util.UUID.randomUUID();
    when(exports.findFor(any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(java.util.Optional.of(job(id, JobState.READY)));
    allowEverything(authorization);
    when(delivery.downloadsFor(any())).thenReturn(List.of());
    when(delivery.downloadScript(any(), any())).thenReturn("#!/usr/bin/env bash\n");

    mvc.perform(get("/api/exports/" + id + "/download.sh").with(alice()))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Content-Disposition", "attachment; filename=\"loggate-" + id + "-download.sh\""));
  }

  @Test
  void streamsTheArchiveAsAnAttachment() throws Exception {
    var id = java.util.UUID.randomUUID();
    when(exports.findFor(any(), org.mockito.ArgumentMatchers.eq(id)))
        .thenReturn(java.util.Optional.of(job(id, JobState.READY)));
    allowEverything(authorization);

    // Streaming responses are dispatched asynchronously, so the result has to
    // be collected in two steps.
    var started = mvc.perform(get("/api/exports/" + id + "/archive.zip").with(alice())).andReturn();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(
                started))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Content-Disposition", "attachment; filename=\"loggate-" + id + ".zip\""));
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
