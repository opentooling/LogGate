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
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.export.ExportEstimate;
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.quota.QuotaDecision;
import com.opentooling.loggate.quota.QuotaGuard;
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
  @MockitoBean private com.opentooling.loggate.authz.NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private ExportService exports;
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

  private static void allowEverything(AuthorizationGate gate) {
    when(gate.check(any(), any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of("platform-dev"), Map.of()));
  }

  private static ExportEstimate estimateOf(long bytes) {
    return new ExportEstimate(
        "{namespace=\"platform-dev\"}",
        Instant.parse("2026-09-20T00:00:00Z"),
        Instant.parse("2026-09-21T00:00:00Z"),
        bytes,
        null,
        Map.of("platform-dev", bytes),
        900,
        96);
  }

  @Test
  void returnsTheEstimateForAnAuthorizedRequest() throws Exception {
    allowEverything(authorization);
    when(exports.preflight(any(), any()))
        .thenReturn(
            new ExportService.Preflight(
                estimateOf(44_040_192L), new QuotaDecision(true, null, 55_050_240L)));

    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimate.estimatedBytes").value(44040192L))
        .andExpect(jsonPath("$.estimate.windowCount").value(96))
        .andExpect(jsonPath("$.estimate.bytesByNamespace['platform-dev']").value(44040192L))
        .andExpect(jsonPath("$.admission.allowed").value(true))
        .andExpect(jsonPath("$.admission.byteLimit").value(55050240L));
  }

  @Test
  void saysWhyAnEstimatedExportWouldBeRefusedRatherThanWaitingForTheSubmission() throws Exception {
    // The verdict travels with the sizing, so the cost and the permission to
    // pay it arrive together.
    allowEverything(authorization);
    when(exports.preflight(any(), any()))
        .thenReturn(
            new ExportService.Preflight(
                estimateOf(80_000_000_000L),
                new QuotaDecision(false, "the export is estimated at 74.5 GB, over the 50.0 GB allowed", 0)));

    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.admission.allowed").value(false))
        .andExpect(
            jsonPath("$.admission.reason")
                .value("the export is estimated at 74.5 GB, over the 50.0 GB allowed"));
  }

  @Test
  void refusesBeforeAskingLokiAboutNamespacesTheCallerCannotRead() throws Exception {
    // Sizing a namespace the caller may not read would leak its volume.
    when(authorization.check(any(), any(), any(), any()))
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

    verify(exports, never()).preflight(any(), any());
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

    verify(authorization, never()).check(any(), any(), any(), any());
  }

  @Test
  void rejectsPodsPickedAndMatchedAtOnce() throws Exception {
    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"namespaces":["platform-dev"], "pods":["api-1"], "podPattern":"api-*",
                     "from":"2026-09-20T00:00:00Z","to":"2026-09-20T01:00:00Z"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("pick pods or give a pod pattern, not both"));

    verify(authorization, never()).check(any(), any(), any(), any());
  }

  @Test
  void rejectsARequestWithNoNamespaces() throws Exception {
    when(access.requiresNamespaces()).thenReturn(true);
    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"namespaces\":[],\"from\":\"2026-09-20T00:00:00Z\",\"to\":\"2026-09-21T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void acceptsNoNamespacesWhenTheAccessModeMeansEveryOne() throws Exception {
    // Open mode: none named is every namespace, still subject to authorization.
    when(access.requiresNamespaces()).thenReturn(false);
    allowEverything(authorization);
    when(exports.preflight(any(), any()))
        .thenReturn(
            new ExportService.Preflight(estimateOf(1024), new QuotaDecision(true, null, 67108864)));

    mvc.perform(
            post("/api/exports/estimate")
                .with(alice())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"namespaces\":[],\"clusters\":[\"edge-eu\"],"
                        + "\"from\":\"2026-09-20T00:00:00Z\",\"to\":\"2026-09-21T00:00:00Z\"}"))
        .andExpect(status().isOk());
    verify(authorization)
        .check(any(), org.mockito.ArgumentMatchers.eq(List.of("edge-eu")), org.mockito.ArgumentMatchers.eq(List.of()), any());
  }

  @Test
  void reportsAPlanItCannotBuildAsTheCallersProblem() throws Exception {
    allowEverything(authorization);
    when(exports.preflight(any(), any()))
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
        null,
        null,
        List.of());
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
    when(authorization.check(any(), any(), any(), any()))
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
        .thenReturn(
            List.of(
                job(java.util.UUID.randomUUID(), JobState.READY),
                job(java.util.UUID.randomUUID(), JobState.RUNNING)));
    when(access.authorize(any(), any(), any()))
        .thenReturn(new AccessDecision(Set.of("platform-dev"), Map.of()));

    mvc.perform(get("/api/exports").with(alice()))
        .andExpect(status().isOk())
        // The job's own fields sit at the top level, beside the verdict.
        .andExpect(jsonPath("$[0].state").value("READY"))
        .andExpect(jsonPath("$[0].downloadable").value(true))
        .andExpect(jsonPath("$[1].downloadable").value(true));
    // Only the finished one has anything to take, so only it is checked, and
    // without an audit row: looking is not taking.
    verify(access, org.mockito.Mockito.times(1)).authorize(any(), any(), any());
    verify(authorization, never()).check(any(), any(), any(), any());
  }

  @Test
  void saysWhenAFinishedExportCanNoLongerBeDownloaded() throws Exception {
    // Made in another access mode, or under access since lost: finished, but
    // the download would be refused, so the page is told rather than surprised.
    when(exports.listFor(any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(job(java.util.UUID.randomUUID(), JobState.READY)));
    when(access.authorize(any(), any(), any()))
        .thenReturn(
            new AccessDecision(Set.of(), Map.of("checkout-prod", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(get("/api/exports").with(alice()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].downloadable").value(false));
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

    // Handing out a link is handing out the data, so it is audited as such,
    // with what the export was rather than only a pointer to it.
    org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.captor();
    verify(auditService)
        .record(
            org.mockito.ArgumentMatchers.eq("alice-subject"),
            org.mockito.ArgumentMatchers.eq(com.opentooling.loggate.audit.AuditAction.DOWNLOAD_LINKS_ISSUED),
            org.mockito.ArgumentMatchers.eq(id),
            detail.capture(),
            any());
    org.assertj.core.api.Assertions.assertThat(detail.getValue())
        .containsEntry("name", "alice")
        .containsEntry("files", 1)
        .containsKeys("namespaces", "clusters", "bytes");
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
    when(authorization.check(any(), any(), any(), any()))
        .thenReturn(
            new AccessDecision(Set.of(), Map.of("platform-dev", DenialReason.NOT_A_GROUP_MEMBER)));

    mvc.perform(get("/api/exports/" + id + "/downloads").with(alice()))
        .andExpect(status().isForbidden());

    verify(delivery, never()).downloadsFor(any());
    // A refused download handed nothing over; the refusal is audited by the gate.
    verify(auditService, never()).record(any(), any(), any(java.util.UUID.class), any(), any());
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
    verify(auditService)
        .record(
            any(),
            org.mockito.ArgumentMatchers.eq(com.opentooling.loggate.audit.AuditAction.DOWNLOAD_SCRIPT_ISSUED),
            org.mockito.ArgumentMatchers.eq(id),
            any(),
            any());
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
    // Recorded as the stream starts, with no file count: it is one archive.
    org.mockito.ArgumentCaptor<Map<String, Object>> detail = org.mockito.ArgumentCaptor.captor();
    verify(auditService)
        .record(
            any(),
            org.mockito.ArgumentMatchers.eq(com.opentooling.loggate.audit.AuditAction.ARCHIVE_DOWNLOADED),
            org.mockito.ArgumentMatchers.eq(id),
            detail.capture(),
            any());
    org.assertj.core.api.Assertions.assertThat(detail.getValue()).doesNotContainKey("files");
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
