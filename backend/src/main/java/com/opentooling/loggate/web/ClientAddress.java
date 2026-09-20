package com.opentooling.loggate.web;

import jakarta.servlet.http.HttpServletRequest;

/** Resolves the caller's address for the audit trail. */
final class ClientAddress {

  private ClientAddress() {}

  /**
   * Prefers the first hop in {@code X-Forwarded-For}, which is what the ingress
   * sets. The header is client-controllable, so this value is evidence of where
   * a request appeared to come from, not proof — it is recorded, never used to
   * make an authorization decision.
   */
  static String of(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
