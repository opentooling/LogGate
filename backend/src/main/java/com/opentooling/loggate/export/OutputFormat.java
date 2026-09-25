package com.opentooling.loggate.export;

/** What an export's files hold, chosen per export. */
public enum OutputFormat {
  /**
   * One JSON object per line: the entry's nanosecond timestamp, its stream
   * labels and the line. Nothing about an entry is lost, and it reads with jq.
   */
  JSON(".jsonl.gz"),
  /**
   * The log lines alone, as they were logged, one per line: for grep, less and
   * tools that expect plain logs. Timestamps and labels are not written, so
   * lines from different pods cannot be told apart afterwards unless they say
   * so themselves.
   */
  RAW(".log.gz");

  private final String extension;

  OutputFormat(String extension) {
    this.extension = extension;
  }

  /** The file extension its parts carry. */
  public String extension() {
    return extension;
  }
}
