package com.opentooling.loggate.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

  @Test
  void prefersTheFirstHopOfForwardedFor() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
    request.setRemoteAddr("10.0.0.1");

    assertThat(ClientAddress.of(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void handlesASingleForwardedForValue() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "203.0.113.5");

    assertThat(ClientAddress.of(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void fallsBackToTheRemoteAddressWhenTheHeaderIsAbsent() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");

    assertThat(ClientAddress.of(request)).isEqualTo("10.0.0.1");
  }

  @Test
  void fallsBackToTheRemoteAddressWhenTheHeaderIsBlank() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "   ");
    request.setRemoteAddr("10.0.0.1");

    assertThat(ClientAddress.of(request)).isEqualTo("10.0.0.1");
  }
}
