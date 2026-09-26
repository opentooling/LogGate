package com.opentooling.loggate.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.export.ExportEstimator;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
import com.opentooling.loggate.quota.QuotaGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Deliberately its own test class.
 *
 * <p>MockMvc's {@code csrf()} post-processor swaps the CsrfTokenRepository in
 * the shared filter chain for a test double that keeps the token in a request
 * attribute instead of a cookie. Any test using it in the same class would make
 * this assertion pass or fail depending on method order, which is exactly the
 * kind of test that stops meaning anything.
 */
@WebMvcTest(NamespaceController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class CsrfCookieTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private com.opentooling.loggate.authz.NamespaceAccess access;
  @MockitoBean private QuotaGuard quotas;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private ExportEstimator estimator;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;
  @MockitoBean private com.opentooling.loggate.pods.PodSource podSource;
  @MockitoBean private com.opentooling.loggate.activity.ActivityRepository activityRepository;
  @MockitoBean private com.opentooling.loggate.audit.AuditService auditService;
  @MockitoBean private com.opentooling.loggate.audit.AuditLog auditLog;

  @Test
  void issuesTheCsrfCookieSoTheSpaCanEchoItBack() throws Exception {
    // Since Spring Security 6 the CSRF token loads lazily, so a JSON API that
    // never reads the token would never write the cookie, and every write from
    // the SPA would be refused with an empty 403.
    mvc.perform(get("/api/me").with(oidcLogin()))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("XSRF-TOKEN"))
        .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
  }

  @Test
  void acceptsAWriteThatEchoesTheCookieInTheHeaderAndRefusesOneThatDoesNot() throws Exception {
    // What the SPA does: read the raw token from the cookie, send it back as
    // X-XSRF-TOKEN. A masked token would be expected from a form, not here.
    jakarta.servlet.http.Cookie token =
        mvc.perform(get("/api/me").with(oidcLogin())).andReturn().getResponse().getCookie("XSRF-TOKEN");

    mvc.perform(
            post("/logout")
                .with(oidcLogin())
                .cookie(token)
                .header("X-XSRF-TOKEN", token.getValue())
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());
    mvc.perform(post("/logout").with(oidcLogin()).cookie(token))
        .andExpect(status().isForbidden());
  }
}
