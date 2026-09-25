package com.opentooling.loggate.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Signing out ends the identity provider's session too, not only LogGate's. */
@WebMvcTest(NamespaceController.class)
@Import({SecurityConfig.class, WebConfig.class, TestClientRegistrationConfig.class})
class SignOutTest {

  @Autowired private MockMvc mvc;
  @Autowired
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      registrations;

  /** Signed in through the application's own registration, as a real session is. */
  private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
          .OidcLoginRequestPostProcessor
      signedIn() {
    return oidcLogin()
        .clientRegistration(registrations.findByRegistrationId("keycloak"))
        .idToken(token -> token.tokenValue("the-id-token"));
  }

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
  void thePageIsToldWhereToGoToSignOutOfTheProvider() throws Exception {
    String body =
        mvc.perform(
                post("/logout")
                    .with(signedIn())
                    .with(csrf())
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.redirect").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The provider's end-session endpoint, told who is signing out and where
    // to send them afterwards: the landing page, which does not sign them in again.
    assertThat(body)
        .contains("https://issuer.test/logout?")
        .contains("id_token_hint=the-id-token")
        .contains("post_logout_redirect_uri=http://localhost/welcome");
  }

  @Test
  void aPlainRequestIsRedirectedThereInstead() throws Exception {
    mvc.perform(post("/logout").with(signedIn()).with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            result ->
                assertThat(result.getResponse().getRedirectedUrl())
                    .startsWith("https://issuer.test/logout?"));
  }

  @Test
  void withNoProviderSessionToEndItStillLandsOnTheLandingPage() throws Exception {
    // Signed in by another route, so there is no provider session to end;
    // the application itself would sign them straight back in.
    mvc.perform(post("/logout").with(oidcLogin()).with(csrf()).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.redirect").value("/welcome"));
  }

  @Test
  void signingOutNeedsTheCsrfToken() throws Exception {
    // Or any page on the internet could sign a user out.
    mvc.perform(post("/logout").with(oidcLogin())).andExpect(status().isForbidden());
  }

  @Test
  void theLandingPageIsServedWithoutASession() throws Exception {
    // Not sent to sign in, which would undo the sign-out.
    int status = mvc.perform(get("/welcome")).andReturn().getResponse().getStatus();
    assertThat(status).isNotIn(302, 401);
  }
}
