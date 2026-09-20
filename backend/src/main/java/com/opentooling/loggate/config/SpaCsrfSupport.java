package com.opentooling.loggate.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF plumbing for a cookie-reading single-page app.
 *
 * <p>Two things are needed beyond choosing a cookie repository, and missing
 * either one produces the same silent failure: every write refused with an
 * empty 403.
 */
final class SpaCsrfSupport {

  private SpaCsrfSupport() {}

  /**
   * Renders the token on every request.
   *
   * <p>Since Spring Security 6 the CSRF token is loaded lazily, and the cookie
   * is only written when something reads the token. A JSON API never reads it,
   * so without this filter the SPA would find no cookie to echo back.
   */
  static final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (token != null) {
        // Reading the value is what causes the repository to persist the cookie.
        token.getToken();
      }
      chain.doFilter(request, response);
    }
  }

  /**
   * Accepts the raw token from the request header, and the BREACH-masked token
   * from a form parameter.
   *
   * <p>The cookie holds the raw token, so a SPA echoing the cookie into a
   * header sends a raw value; the default XOR handler would reject it.
   */
  static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        java.util.function.Supplier<CsrfToken> deferredCsrfToken) {
      masked.handle(request, response, deferredCsrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
      if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
        return super.resolveCsrfTokenValue(request, csrfToken);
      }
      return masked.resolveCsrfTokenValue(request, csrfToken);
    }
  }
}
