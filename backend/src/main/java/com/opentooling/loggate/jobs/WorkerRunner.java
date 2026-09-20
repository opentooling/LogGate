package com.opentooling.loggate.jobs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Keeps a fixed number of workers pulling from the queue.
 *
 * <p>Virtual threads: the work is almost entirely waiting on Loki and object
 * storage, so concurrency is bounded to protect <em>them</em>, not to ration
 * platform threads. The bound is the point - it is what stops a large export
 * from starving the people reading dashboards.
 */
public class WorkerRunner implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

  private final ExportWorker worker;
  private final int concurrency;
  private final Duration idlePause;
  private final AtomicBoolean running = new AtomicBoolean();
  private final List<Thread> threads = new ArrayList<>();

  public WorkerRunner(ExportWorker worker, int concurrency, Duration idlePause) {
    this.worker = worker;
    this.concurrency = concurrency;
    this.idlePause = idlePause;
  }

  @Override
  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    for (int i = 0; i < concurrency; i++) {
      Thread thread = Thread.ofVirtual().name("export-worker-" + i).start(this::loop);
      threads.add(thread);
    }
    log.info("started {} export workers", concurrency);
  }

  @Override
  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    threads.forEach(Thread::interrupt);
    threads.clear();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void loop() {
    while (running.get()) {
      try {
        if (!worker.runOnce()) {
          Thread.sleep(idlePause);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // A worker that dies stops draining the queue, so it keeps going and
        // the window it was holding returns to the queue when its lease lapses.
        log.error("export worker failed, continuing", e);
        try {
          Thread.sleep(idlePause);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
