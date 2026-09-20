package com.opentooling.loggate.jobs;

/** Thrown when an export produces more than it was admitted for. */
public class ByteLimitExceededException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ByteLimitExceededException(long writtenBytes, long limitBytes) {
    super("export wrote %d bytes, over its limit of %d".formatted(writtenBytes, limitBytes));
  }
}
