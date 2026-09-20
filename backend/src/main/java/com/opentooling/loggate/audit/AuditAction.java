package com.opentooling.loggate.audit;

/** Auditable actions. Stored by name, so entries must not be renamed. */
public enum AuditAction {
  /** A caller was refused one or more namespaces. */
  NAMESPACE_ACCESS_DENIED
}
