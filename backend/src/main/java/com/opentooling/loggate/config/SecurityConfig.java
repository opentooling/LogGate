package com.opentooling.loggate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Backend-for-frontend: the OIDC authorization code flow is terminated here and
 * the browser holds a session cookie, never a token. That keeps access tokens
 * out of reach of anything running in the page, and keeps the SPA same-origin
 * with the API so there is no CORS surface to get wrong.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

  public SecurityConfig() {}

  @org.springframework.beans.factory.annotation.Autowired
  public void initCustomCa(
      org.springframework.beans.factory.ObjectProvider<LogGateProperties> propertiesProvider) {
    LogGateProperties properties = propertiesProvider.getIfAvailable();
    if (properties != null && !properties.oidc().caCertificate().isBlank()) {
      com.opentooling.loggate.security.CaCertificates.configureDefaultSslContext(
          java.nio.file.Path.of(properties.oidc().caCertificate()));
    }
  }

  /**
   * Signs users in as usual, and keeps the client roles from their access
   * token, which is the only place Keycloak puts them by default.
   */
  @Bean
  com.opentooling.loggate.security.ClientRoleOidcUserService oidcUserService(
      tools.jackson.databind.ObjectMapper json) {
    return new com.opentooling.loggate.security.ClientRoleOidcUserService(json);
  }

  @Bean
  SecurityFilterChain filterChain(
      HttpSecurity http,
      com.opentooling.loggate.security.ClientRoleOidcUserService oidcUserService)
      throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            login -> login.userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService)))
        .logout(logout -> logout.logoutSuccessUrl("/").permitAll())
        // The SPA reads the CSRF cookie and echoes it back, so it must not be
        // HttpOnly. It is not a secret: it defends against cross-origin writes.
        //
        // The request handler matters as much as the repository. Since Spring
        // Security 6 the token is loaded lazily, so the cookie is only written
        // when something actually reads the token - and a JSON API never does.
        // The SPA would then find no cookie to echo and every write would be
        // refused with an empty 403. Clearing the request attribute name opts
        // out of deferred loading, so the cookie is issued on every response.
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new SpaCsrfSupport.SpaCsrfTokenRequestHandler()))
        .addFilterAfter(new SpaCsrfSupport.CsrfCookieFilter(), BasicAuthenticationFilter.class)
        // An unauthenticated API call should be a 401 the SPA can act on, not a
        // redirect to Keycloak that a fetch() cannot follow usefully.
        .exceptionHandling(
            handling ->
                handling.defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    request -> request.getRequestURI().startsWith("/api/")))
        .build();
  }
}
