package com.opentooling.loggate.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminPolicyTest {

  private static AuthenticatedUser holding(String... roles) {
    return new AuthenticatedUser("s", "n", Set.of(), Set.of(roles));
  }

  @Test
  void anAdministratorHoldsTheRole() {
    AdminPolicy policy = new AdminPolicy(" loggate-admin ");
    assertThat(policy.isAdmin(holding("loggate-admin", "export-logs"))).isTrue();
    assertThat(policy.isAdmin(holding("export-logs"))).isFalse();
    assertThat(policy.role()).isEqualTo("loggate-admin");
  }

  @Test
  void noRoleMakesNobodyAnAdministratorRatherThanEverybody() {
    for (AdminPolicy policy : new AdminPolicy[] {new AdminPolicy(""), new AdminPolicy(null)}) {
      assertThat(policy.isAdmin(holding(""))).isFalse();
      assertThat(policy.isAdmin(holding())).isFalse();
      assertThat(policy.role()).isEmpty();
    }
  }
}
