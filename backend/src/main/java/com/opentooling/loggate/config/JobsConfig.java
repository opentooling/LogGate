package com.opentooling.loggate.config;

import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.export.ExportEstimator;
import com.opentooling.loggate.export.ExportService;
import com.opentooling.loggate.export.WindowPager;
import com.opentooling.loggate.export.WindowPlanner;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.jobs.ExportWorker;
import com.opentooling.loggate.jobs.JobFinalizer;
import com.opentooling.loggate.jobs.WorkerRunner;
import com.opentooling.loggate.observability.ExportMetrics;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.storage.ObjectStore;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.ObjectMapper;

/** The queue, the workers, and the storage they write to. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class JobsConfig {

  @Bean
  TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  @Bean
  ExportJobRepository exportJobRepository(JdbcClient db, TransactionTemplate transactions) {
    return new ExportJobRepository(db, transactions);
  }

  @Bean
  com.opentooling.loggate.activity.ActivityRepository activityRepository(
      JdbcClient db, ExportJobRepository jobs) {
    return new com.opentooling.loggate.activity.ActivityRepository(db, jobs, Clock.systemUTC());
  }

  @Bean
  QuotaGuard quotaGuard(ExportJobRepository jobs, LogGateProperties properties) {
    return new QuotaGuard(jobs, properties, Clock.systemUTC());
  }

  @Bean
  ExportMetrics exportMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
    return new ExportMetrics(registry);
  }

  @Bean
  com.opentooling.loggate.observability.QueueGauges queueGauges(
      io.micrometer.core.instrument.MeterRegistry registry, ExportJobRepository jobs) {
    return new com.opentooling.loggate.observability.QueueGauges(registry, jobs);
  }

  @Bean
  ExportService exportService(
      ExportEstimator estimator,
      WindowPlanner planner,
      QuotaGuard quotas,
      ExportJobRepository jobs,
      com.opentooling.loggate.authz.NamespaceAccess access,
      AuditService audit,
      ExportMetrics metrics) {
    return new ExportService(estimator, planner, quotas, jobs, access, audit, metrics);
  }

  @Bean
  S3Client s3Client(LogGateProperties properties) {
    return com.opentooling.loggate.storage.S3Clients.client(properties.storage());
  }

  @Bean
  public S3Presigner s3Presigner(LogGateProperties properties) {
    // Presigned URLs are opened by a browser, so they must carry the URL a
    // browser can reach, which is not always the in-cluster endpoint.
    return com.opentooling.loggate.storage.S3Clients.presigner(properties.storage());
  }

  @Bean
  ObjectStore objectStore(
      S3Client s3Client, S3Presigner s3Presigner, LogGateProperties properties) {
    return new com.opentooling.loggate.storage.S3ObjectStore(
        s3Client, s3Presigner, properties.storage().bucket());
  }

  @Bean
  ExportWorker exportWorker(
      ExportJobRepository jobs,
      WindowPager pager,
      ObjectStore store,
      ObjectMapper json,
      LogGateProperties properties,
      ExportMetrics metrics) {
    LogGateProperties.Execution execution = properties.execution();
    // The owner identifies which process holds a lease, so a human reading the
    // table can tell a stuck worker from a busy one.
    String owner = System.getenv().getOrDefault("HOSTNAME", "local") + "/" + UUID.randomUUID();
    return new ExportWorker(
        jobs, pager, store, json, owner, execution.lease(), execution.maxAttempts(), metrics);
  }

  @Bean
  com.opentooling.loggate.delivery.ManifestBuilder manifestBuilder(ExportJobRepository jobs) {
    return new com.opentooling.loggate.delivery.ManifestBuilder(jobs, Clock.systemUTC());
  }

  @Bean
  com.opentooling.loggate.delivery.DeliveryService deliveryService(
      ExportJobRepository jobs, ObjectStore store, LogGateProperties properties) {
    return new com.opentooling.loggate.delivery.DeliveryService(jobs, store, properties);
  }

  @Bean
  JobFinalizer jobFinalizer(
      ExportJobRepository jobs,
      ObjectStore store,
      com.opentooling.loggate.delivery.ManifestBuilder manifests,
      ObjectMapper json,
      LogGateProperties properties,
      ExportMetrics metrics) {
    return new JobFinalizer(
        jobs,
        store,
        manifests,
        json,
        properties.execution().retention(),
        Clock.systemUTC(),
        metrics);
  }

  /** Workers run unless switched off, which is what the web slice tests do. */
  @Bean
  @ConditionalOnProperty(prefix = "loggate.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
  WorkerRunner workerRunner(ExportWorker worker, LogGateProperties properties) {
    LogGateProperties.Execution execution = properties.execution();
    return new WorkerRunner(worker, execution.workers(), execution.idlePause());
  }

  @Bean
  @ConditionalOnProperty(prefix = "loggate.execution", name = "enabled", havingValue = "true", matchIfMissing = true)
  JobMaintenance jobMaintenance(JobFinalizer finalizer) {
    return new JobMaintenance(finalizer);
  }

  /** Periodic job-level transitions, kept off the worker path. */
  public static class JobMaintenance {

    private final JobFinalizer finalizer;

    JobMaintenance(JobFinalizer finalizer) {
      this.finalizer = finalizer;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${loggate.execution.maintenance-interval:5s}")
    public void run() {
      finalizer.publishFinished();
      finalizer.closeCancelled();
      finalizer.sweepExpired();
    }
  }
}
