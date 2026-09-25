package com.opentooling.loggate.jobs;

/** Thrown when an export produces more than it was admitted for. */
public class ByteLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ByteLimitExceededException(long logBytes, long limitBytes) {
    super("export read %d bytes of logs, over its limit of %d".formatted(logBytes, limitBytes));
  }
}
