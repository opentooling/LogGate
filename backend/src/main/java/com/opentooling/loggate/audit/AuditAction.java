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
  EXPORT_CANCELLED
}
