package com.opentooling.loggate.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditLog;
import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.quota.QuotaGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class AuditControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;
  @MockitoBean private com.opentooling.loggate.pods.PodSource podSource;
  @MockitoBean private com.opentooling.loggate.activity.ActivityRepository activityRepository;
  @MockitoBean private com.opentooling.loggate.audit.AuditService auditService;
  @MockitoBean private AuditLog log;

  @Test
  void listsDownloadsForAnAdministratorAPageAtATime() throws Exception {
    UUID job = UUID.randomUUID();
    when(log.downloads(2, 90L))
        .thenReturn(
            new AuditLog.Page(
                List.of(
                    new AuditLog.Download(
                        88, Instant.parse("2026-09-20T10:00:00Z"), "alice-subject", "alice",
                        AuditAction.ARCHIVE_DOWNLOADED, job, List.of("platform-dev"),
                        List.of("k3d-loggate"), null, 1024L, "10.0.0.1")),
                87L));
    when(log.totals()).thenReturn(Map.of(AuditAction.ARCHIVE_DOWNLOADED, 3L));

    mvc.perform(get("/api/audit/downloads?limit=2&before=90").with(ActivityControllerTest.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.events[0].name").value("alice"))
        .andExpect(jsonPath("$.page.events[0].action").value("ARCHIVE_DOWNLOADED"))
        .andExpect(jsonPath("$.page.events[0].jobId").value(job.toString()))
        .andExpect(jsonPath("$.page.events[0].sourceIp").value("10.0.0.1"))
        .andExpect(jsonPath("$.page.next").value(87))
        .andExpect(jsonPath("$.totals.ARCHIVE_DOWNLOADED").value(3));
  }

  @Test
  void startsFromTheNewestFiftyByDefault() throws Exception {
    when(log.downloads(50, null)).thenReturn(new AuditLog.Page(List.of(), null));
    mvc.perform(get("/api/audit/downloads").with(ActivityControllerTest.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.next").doesNotExist());
  }

  @Test
  void refusesAPageItWillNotServe() throws Exception {
    for (String limit : List.of("0", "201")) {
      mvc.perform(get("/api/audit/downloads?limit=" + limit).with(ActivityControllerTest.admin()))
          .andExpect(status().isBadRequest());
    }
    verify(log, never()).downloads(anyInt(), any());
  }

  @Test
  void isForAdministratorsOnly() throws Exception {
    mvc.perform(get("/api/audit/downloads").with(oidcLogin())).andExpect(status().isForbidden());
    verify(log, never()).downloads(anyInt(), any());
  }

  @Test
  void saysWhenNoAdministratorRoleIsConfigured() {
    org.assertj.core.api.Assertions.assertThat(
            AuditController.notAdmin(new com.opentooling.loggate.security.AdminPolicy("")))
        .isEqualTo("no administrator role is configured here");
  }
}
