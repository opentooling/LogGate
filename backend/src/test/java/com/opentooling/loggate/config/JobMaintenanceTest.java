package com.opentooling.loggate.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.opentooling.loggate.jobs.JobFinalizer;
import org.junit.jupiter.api.Test;

class JobMaintenanceTest {

  @Test
  void publishesFinishedJobsAndClosesCancelledOnes() {
    JobFinalizer finalizer = mock(JobFinalizer.class);

    new JobsConfig().jobMaintenance(finalizer).run();

    verify(finalizer).publishFinished();
    verify(finalizer).closeCancelled();
  }
}
