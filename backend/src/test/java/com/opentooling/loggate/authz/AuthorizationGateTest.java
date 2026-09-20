package com.opentooling.loggate.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.namespaces.FakeNamespaceCatalog;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorizationGateTest {

  private final AuditService audit = mock(AuditService.class);
  private final AuthorizationGate gate =
      new AuthorizationGate(
          new NamespaceAuthorizer(
              new FakeNamespaceCatalog().with("platform-dev", "platform", "ad-platform-dev")),
          audit);

  private static AuthenticatedUser alice() {
    return new AuthenticatedUser("alice-subject", "alice", Set.of("ad-platform-dev"));
  }

  @Test
  void allowsWithoutAuditingWhenNothingIsRefused() {
    // Successful checks are not audit events; the export itself will be.
    AccessDecision decision = gate.check(alice(), List.of("platform-dev"), "10.0.0.1");

    assertThat(decision.isFullyAllowed()).isTrue();
    verify(audit, never()).record(anyString(), any(), any(), anyString());
  }

  @Test
  void auditsEveryRefusal() {
    // A refusal that is not recorded is the one an investigation will want.
    AccessDecision decision = gate.check(alice(), List.of("payments-dev"), "10.0.0.2");

    assertThat(decision.denied()).containsEntry("payments-dev", DenialReason.UNKNOWN_NAMESPACE);
    verify(audit)
        .record(
            eq("alice-subject"), eq(AuditAction.NAMESPACE_ACCESS_DENIED), any(), eq("10.0.0.2"));
  }
}
