package com.opentooling.loggate.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentooling.loggate.authz.AuthorizationGate;
import com.opentooling.loggate.authz.NamespaceAuthorizer;
import com.opentooling.loggate.export.ExportEstimator;
import com.opentooling.loggate.config.SecurityConfig;
import com.opentooling.loggate.config.WebConfig;
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

  @MockitoBean private NamespaceAuthorizer authorizer;
  @MockitoBean private AuthorizationGate authorization;
  @MockitoBean private ExportEstimator estimator;
  @MockitoBean private com.opentooling.loggate.export.ExportService exports;
  @MockitoBean private com.opentooling.loggate.delivery.DeliveryService delivery;

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
}
