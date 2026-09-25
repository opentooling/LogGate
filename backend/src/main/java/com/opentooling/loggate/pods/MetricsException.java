package com.opentooling.loggate.pods;

/** The metrics endpoint failed, or answered with something unusable. */
public class MetricsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MetricsException(String message) {
    super(message);
  }

  public MetricsException(String message, Throwable cause) {
    super(message, cause);
  }
}
