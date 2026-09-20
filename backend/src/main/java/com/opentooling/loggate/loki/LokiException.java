package com.opentooling.loggate.loki;

/** A call to Loki failed, or returned something that cannot be used. */
public class LokiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public LokiException(String message) {
    super(message);
  }

  public LokiException(String message, Throwable cause) {
    super(message, cause);
  }
}
