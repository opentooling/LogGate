package com.opentooling.loggate.quota;

/**
 * Who an export is charged to.
 *
 * <p>A team, when namespaces are owned by teams. In open mode there are no
 * teams, so the person exporting is charged instead: a budget with nobody to
 * charge would be no budget at all.
 *
 * @param id what spending is recorded against
 * @param label what to call it on screen
 */
public record BudgetHolder(String id, String label) {

  /** A team, recorded and shown by its name. */
  public static BudgetHolder team(String team) {
    return new BudgetHolder(team, team);
  }

  /**
   * A person. Recorded by their stable subject, which survives a change of
   * username, and prefixed so it can never collide with a team's name.
   */
  public static BudgetHolder person(String subject, String name) {
    return new BudgetHolder("user:" + subject, name);
  }
}
