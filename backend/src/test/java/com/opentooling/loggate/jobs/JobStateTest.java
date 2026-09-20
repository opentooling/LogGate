package com.opentooling.loggate.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobStateTest {

  @Test
  void countsOnlyUnfinishedJobsAsActive() {
    // Active states are what concurrency quotas count, so a finished job must
    // never hold a slot open.
    assertThat(JobState.QUEUED.isActive()).isTrue();
    assertThat(JobState.RUNNING.isActive()).isTrue();
    assertThat(JobState.FINALIZING.isActive()).isTrue();
    assertThat(JobState.READY.isActive()).isFalse();
    assertThat(JobState.FAILED.isActive()).isFalse();
    assertThat(JobState.CANCELLED.isActive()).isFalse();
    assertThat(JobState.EXPIRED.isActive()).isFalse();
  }

  @Test
  void namesTheActiveStatesForUseInSql() {
    assertThat(JobState.activeNames())
        .containsExactlyInAnyOrder("QUEUED", "PLANNED", "RUNNING", "FINALIZING");
  }
}
