package com.opentooling.loggate.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkerRunnerTest {

  private final ExportWorker worker = mock(ExportWorker.class);

  @Test
  void keepsPullingFromTheQueueWhileRunning() {
    AtomicInteger calls = new AtomicInteger();
    when(worker.runOnce()).thenAnswer(invocation -> calls.incrementAndGet() < 5);
    var runner = new WorkerRunner(worker, 1, Duration.ofMillis(10));

    runner.start();
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 5);
      assertThat(runner.isRunning()).isTrue();
    } finally {
      runner.stop();
    }

    assertThat(runner.isRunning()).isFalse();
  }

  @Test
  void keepsGoingAfterAWorkerFails() {
    // A worker that dies stops draining the queue, so a failure must not end
    // the loop; the window it held returns when its lease lapses.
    AtomicInteger calls = new AtomicInteger();
    when(worker.runOnce())
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database went away");
              }
              return false;
            });
    var runner = new WorkerRunner(worker, 1, Duration.ofMillis(10));

    runner.start();
    try {
      await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);
    } finally {
      runner.stop();
    }
  }

  @Test
  void startingTwiceDoesNotDoubleTheWorkers() {
    when(worker.runOnce()).thenReturn(false);
    var runner = new WorkerRunner(worker, 1, Duration.ofMillis(50));

    runner.start();
    runner.start();
    try {
      assertThat(runner.isRunning()).isTrue();
    } finally {
      runner.stop();
      runner.stop();
    }
  }
}
