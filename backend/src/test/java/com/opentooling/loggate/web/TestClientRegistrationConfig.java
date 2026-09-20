package com.opentooling.loggate.web;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * A stand-in OIDC registration. {@code oauth2Login()} needs a registration to
 * exist; these tests drive the security filter chain directly, so it never has
 * to resolve against a real issuer.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClientRegistrationConfig {

  @Bean
  ClientRegistrationRepository clientRegistrationRepository() {
    return new InMemoryClientRegistrationRepository(
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("loggate")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid")
            .authorizationUri("https://issuer.test/auth")
            .tokenUri("https://issuer.test/token")
            .jwkSetUri("https://issuer.test/jwks")
            .userNameAttributeName("preferred_username")
            .build());
  }
}
