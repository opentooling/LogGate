package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SpaCsrfSupportTest {

  private static final String HEADER = "X-XSRF-TOKEN";

  @Test
  void theFilterRendersTheTokenSoTheCookieGetsWritten() throws Exception {
    CsrfToken token = mock(CsrfToken.class);
    var request = new MockHttpServletRequest();
    request.setAttribute(CsrfToken.class.getName(), token);
    var chain = new MockFilterChain();

    new SpaCsrfSupport.CsrfCookieFilter()
        .doFilter(request, new MockHttpServletResponse(), chain);

    // Reading the value is what causes the repository to persist the cookie.
    verify(token).getToken();
    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void theFilterPassesThroughWhenThereIsNoToken() throws Exception {
    var request = new MockHttpServletRequest();
    var chain = new MockFilterChain();

    new SpaCsrfSupport.CsrfCookieFilter()
        .doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(request);
  }

  @Test
  void theHandlerTakesTheRawTokenFromTheRequestHeader() {
    // The cookie holds the raw token, so a SPA echoing it into a header sends a
    // raw value; the default XOR handler would reject it.
    var token = new DefaultCsrfToken(HEADER, "_csrf", "raw-token-value");
    var request = new MockHttpServletRequest();
    request.addHeader(HEADER, "raw-token-value");

    String resolved = new SpaCsrfSupport.SpaCsrfTokenRequestHandler()
        .resolveCsrfTokenValue(request, token);

    assertThat(resolved).isEqualTo("raw-token-value");
  }

  @Test
  void theHandlerFallsBackToTheMaskedTokenWhenThereIsNoHeader() {
    var token = new DefaultCsrfToken(HEADER, "_csrf", "raw-token-value");
    var request = new MockHttpServletRequest();

    String resolved = new SpaCsrfSupport.SpaCsrfTokenRequestHandler()
        .resolveCsrfTokenValue(request, token);

    // No header and no form parameter: nothing to resolve, so the request is
    // refused rather than silently accepted.
    assertThat(resolved).isNull();
  }

  @Test
  void theHandlerPublishesTheTokenForFormRendering() {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    CsrfToken token = mock(CsrfToken.class);
    when(token.getParameterName()).thenReturn("_csrf");

    new SpaCsrfSupport.SpaCsrfTokenRequestHandler().handle(request, response, () -> token);

    assertThat(request.getAttribute(CsrfToken.class.getName())).isNotNull();
  }
}
