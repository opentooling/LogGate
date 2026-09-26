package com.opentooling.loggate.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * Signs out of the identity provider as well as LogGate.
 *
 * <p>Ending only LogGate's session signs nobody out: the provider's session is
 * still there, so the next page load signs the same person straight back in.
 * This sends the browser on to the provider's end-session endpoint, which ends
 * that session too and returns to LogGate's landing page.
 *
 * <p>The page signs out with a fetch, because the CSRF token it holds can only
 * be sent as a header, and a fetch cannot follow a redirect to another origin.
 * So a request that asks for JSON is answered with where to go rather than
 * sent there; anything else is redirected as usual.
 */
public final class SpaLogoutSuccessHandler extends OidcClientInitiatedLogoutSuccessHandler {

  private final ObjectMapper json;

  /**
   * @param landingPage where the provider returns to once it has signed the
   *     user out: a page served without a session, so it does not sign them in
   *     again
   */
  public SpaLogoutSuccessHandler(
      ClientRegistrationRepository registrations, String landingPage, ObjectMapper json) {
    super(registrations);
    this.json = json;
    setPostLogoutRedirectUri("{baseUrl}" + landingPage);
    // Without an OIDC session to end, for instance after it expired, land on
    // the same page rather than on the application, which would sign in again.
    setDefaultTargetUrl(landingPage);
  }

  @Override
  public void onLogoutSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    String accept = request.getHeader("Accept");
    if (accept == null || !accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
      super.onLogoutSuccess(request, response, authentication);
      return;
    }
    String target = determineTargetUrl(request, response, authentication);
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    json.writeValue(response.getWriter(), Map.of("redirect", target));
  }
}
