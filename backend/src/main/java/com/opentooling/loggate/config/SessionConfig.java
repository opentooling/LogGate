package com.opentooling.loggate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.security.jackson.SecurityJacksonModules;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sessions live in PostgreSQL so that login works with more than one replica.
 *
 * <p>The OAuth2 authorization request is held in the session between the
 * redirect to Keycloak and the callback. With per-pod sessions those two
 * requests can land on different pods - which a rolling update guarantees
 * briefly - and the login fails with no useful error.
 *
 * <p>They are serialized as <strong>JSON</strong> rather than by Java
 * serialization, which is the part that is easy to get wrong: Spring Security
 * 7's {@code OAuth2AuthorizationRequest} is not {@code Serializable}, so the
 * default converter silently fails to store it and the login breaks in a
 * different way. Spring Security ships Jackson mixins for exactly these types.
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {

  /**
   * Spring Session looks up a conversion service by this name, so the bean name
   * is part of the contract rather than a stylistic choice.
   */
  @Bean("springSessionConversionService")
  GenericConversionService springSessionConversionService() {
    // OIDC claims carry the issuer as a java.net.URL, which is not on Spring
    // Security's default allowlist. Without this, storing the security context
    // succeeds and reading it back fails - so login works until the request
    // that needs the session lands on another pod.
    var allowed =
        tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(java.net.URL.class)
            .allowIfSubType(java.net.URI.class);

    ObjectMapper mapper =
        JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(getClass().getClassLoader(), allowed))
            .build();

    GenericConversionService conversions = new GenericConversionService();
    conversions.addConverter(Object.class, byte[].class, mapper::writeValueAsBytes);
    conversions.addConverter(
        byte[].class, Object.class, source -> mapper.readValue(source, Object.class));
    return conversions;
  }
}
