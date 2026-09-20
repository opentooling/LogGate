package com.opentooling.loggate.quota;

/**
 * Whether an export may run, and under what cap.
 *
 * @param admitted whether the job may be created
 * @param reason why it was refused, when it was
 * @param byteLimit the cap the job runs under, when admitted
 */
public record QuotaDecision(boolean admitted, String reason, long byteLimit) {

  static QuotaDecision refused(String reason) {
    return new QuotaDecision(false, reason, 0);
  }

  static QuotaDecision admitted(long byteLimit) {
    return new QuotaDecision(true, null, byteLimit);
  }
}
