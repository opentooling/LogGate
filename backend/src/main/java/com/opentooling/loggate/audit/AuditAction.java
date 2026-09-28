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
  ARCHIVE_DOWNLOADED,
  /** Every window was written and the export's files are ready to download. */
  EXPORT_COMPLETED,
  /** The export stopped without producing its files: a byte cap, or a source that kept failing. */
  EXPORT_FAILED;

  /** The actions that hand an export's data to someone. */
  public static final java.util.List<AuditAction> DOWNLOADS =
      java.util.List.of(DOWNLOAD_LINKS_ISSUED, DOWNLOAD_SCRIPT_ISSUED, ARCHIVE_DOWNLOADED);

  /** An export's life, from being asked for to its files existing or not. */
  public static final java.util.List<AuditAction> EXPORTS =
      java.util.List.of(
          EXPORT_SUBMITTED, EXPORT_REFUSED, EXPORT_CANCELLED, EXPORT_COMPLETED, EXPORT_FAILED);

  /** Asking for logs one may not have. */
  public static final java.util.List<AuditAction> DENIALS = java.util.List.of(NAMESPACE_ACCESS_DENIED);
}
