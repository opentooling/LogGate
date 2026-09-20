package com.opentooling.loggate.authz;

/** Why a namespace was refused. Recorded verbatim in the audit trail. */
public enum DenialReason {
  /** The namespace does not exist, or the catalog has never seen it. */
  UNKNOWN_NAMESPACE,
  /** The namespace exists but carries no usable team label, so it has no owner. */
  UNLABELLED_NAMESPACE,
  /** The namespace is owned by a group the caller is not a member of. */
  NOT_A_GROUP_MEMBER,
  /** The catalog is not synced, so no namespace can be authorized. */
  CATALOG_UNAVAILABLE
}
