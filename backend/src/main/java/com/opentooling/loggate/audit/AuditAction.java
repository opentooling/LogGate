package com.opentooling.loggate.audit;

/** Auditable actions. Stored by name, so entries must not be renamed. */
public enum AuditAction {
  /** A caller was refused one or more namespaces. */
  NAMESPACE_ACCESS_DENIED,
  /** An export was accepted and queued. */
  EXPORT_SUBMITTED,
  /** An export was refused by a quota. */
  EXPORT_REFUSED,
  /** An export was asked to stop. */
  EXPORT_CANCELLED,
  /**
   * Presigned links to an export's files were issued. The files are fetched
   * from object storage directly, so this is when LogGate last sees the
   * download: what it records is that the links were handed out.
   */
  DOWNLOAD_LINKS_ISSUED,
  /** The download script, which carries presigned links to every file, was issued. */
  DOWNLOAD_SCRIPT_ISSUED,
  /** The export was streamed as one .zip through LogGate itself. */
  ARCHIVE_DOWNLOADED;

  /** The actions that hand an export's data to someone. */
  public static final java.util.List<AuditAction> DOWNLOADS =
      java.util.List.of(DOWNLOAD_LINKS_ISSUED, DOWNLOAD_SCRIPT_ISSUED, ARCHIVE_DOWNLOADED);
}
