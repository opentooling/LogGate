package com.opentooling.loggate.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpaLogoutSuccessHandlerTest {

  @Test
  void escapesTheUrlAsAJsonString() {
    assertThat(SpaLogoutSuccessHandler.jsonString("https://x/?a=1&b=\"2\"\\\n"))
        .isEqualTo("\"https://x/?a=1&b=\\\"2\\\"\\\\\\u000a\"");
  }
}
