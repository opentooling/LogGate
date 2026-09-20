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
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.storage.ObjectStore;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
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
  QuotaGuard quotaGuard(ExportJobRepository jobs, LogGateProperties properties) {
    return new QuotaGuard(jobs, properties);
  }

  @Bean
  ExportService exportService(
      ExportEstimator estimator,
      WindowPlanner planner,
      QuotaGuard quotas,
      ExportJobRepository jobs,
      AuditService audit) {
    return new ExportService(estimator, planner, quotas, jobs, audit);
  }

  @Bean
  S3Client s3Client(LogGateProperties properties) {
    LogGateProperties.Storage storage = properties.storage();
    return S3Client.builder()
        .endpointOverride(URI.create(storage.endpoint()))
        .region(Region.of(storage.region()))
        // Static keys when configured, otherwise the default chain, so a
        // production deployment can use an IAM role instead of a secret.
        .credentialsProvider(
            storage.accessKey().isBlank()
                ? software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
        // MinIO addresses buckets by path, not by subdomain.
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyle()).build())
        .build();
  }

  @Bean
  ObjectStore objectStore(S3Client s3Client, LogGateProperties properties) {
    return new com.opentooling.loggate.storage.S3ObjectStore(s3Client, properties.storage().bucket());
  }

  @Bean
  ExportWorker exportWorker(
      ExportJobRepository jobs,
      WindowPager pager,
      ObjectStore store,
      ObjectMapper json,
      LogGateProperties properties) {
    LogGateProperties.Execution execution = properties.execution();
    // The owner identifies which process holds a lease, so a human reading the
    // table can tell a stuck worker from a busy one.
    String owner = System.getenv().getOrDefault("HOSTNAME", "local") + "/" + UUID.randomUUID();
    return new ExportWorker(
        jobs, pager, store, json, owner, execution.lease(), execution.maxAttempts());
  }

  @Bean
  JobFinalizer jobFinalizer(ExportJobRepository jobs, ObjectStore store, LogGateProperties properties) {
    return new JobFinalizer(jobs, store, properties.execution().retention(), Clock.systemUTC());
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
    }
  }
}
