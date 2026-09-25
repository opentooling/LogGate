package com.opentooling.loggate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.quota.QuotaGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** What someone who is not signed in may see, and where they are sent. */
@WebMvcTest(NamespaceController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class PublicPagesTest {

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

  @Test
  void someoneNotSignedInLandsOnTheWelcomePageRatherThanTheProvider() throws Exception {
    mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/welcome"));
  }

  @Test
  void theApiStillAnswersWith401() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void thePublicPagesAreServedAtShortPathsWithoutASession() throws Exception {
    mvc.perform(get("/welcome")).andExpect(forwardedUrl("/welcome.html"));
    mvc.perform(get("/guide")).andExpect(forwardedUrl("/guide/index.html"));
    mvc.perform(get("/guide/")).andExpect(forwardedUrl("/guide/index.html"));
  }

  @Test
  void theirFilesAreNotBehindTheLogin() throws Exception {
    for (String path :
        new String[] {
          "/welcome.html", "/guide/index.html", "/guide/images/01-sign-in.png",
          "/site.css", "/theme-boot.js"
        }) {
      int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
      assertThat(status).as(path).isNotIn(302, 401);
    }
  }

  @Test
  void theApplicationItselfStillNeedsASession() throws Exception {
    // A path that is not public is sent to the welcome page, not served.
    mvc.perform(get("/index.html")).andExpect(redirectedUrl("/welcome"));
    // And is served once signed in.
    int status = mvc.perform(get("/index.html").with(oidcLogin())).andReturn().getResponse().getStatus();
    assertThat(status).isNotIn(302, 401);
  }
}
