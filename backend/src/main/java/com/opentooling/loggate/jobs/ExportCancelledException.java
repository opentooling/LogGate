package com.opentooling.loggate.jobs;

/** Thrown to unwind a window when its job has been asked to stop. */
public class ExportCancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ExportCancelledException() {
    super("export cancelled");
  }
}
