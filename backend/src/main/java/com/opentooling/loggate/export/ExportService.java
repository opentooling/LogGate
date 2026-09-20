package com.opentooling.loggate.export;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.jobs.NewJob;
import com.opentooling.loggate.namespaces.NamespaceCatalog;
import com.opentooling.loggate.namespaces.NamespaceInfo;
import com.opentooling.loggate.quota.QuotaDecision;
import com.opentooling.loggate.quota.QuotaGuard;
import com.opentooling.loggate.security.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an authorized request into a queued job: size it, admit it against
 * quota, plan it, record it.
 *
 * <p>Sizing happens before admission because the size is what admission is
 * mostly about, and planning happens after because the plan is derived from
 * the size.
 */
public class ExportService {

  private final ExportEstimator estimator;
  private final WindowPlanner planner;
  private final QuotaGuard quotas;
  private final ExportJobRepository jobs;
  private final NamespaceCatalog namespaces;
  private final AuditService audit;
  private final com.opentooling.loggate.observability.ExportMetrics metrics;

  public ExportService(
      ExportEstimator estimator,
      WindowPlanner planner,
      QuotaGuard quotas,
      ExportJobRepository jobs,
      NamespaceCatalog namespaces,
      AuditService audit,
      com.opentooling.loggate.observability.ExportMetrics metrics) {
    this.estimator = estimator;
    this.planner = planner;
    this.quotas = quotas;
    this.jobs = jobs;
    this.namespaces = namespaces;
    this.audit = audit;
    this.metrics = metrics;
  }

  /** The outcome of submitting an export.
   *
   * @param job the queued job, when it was accepted
   * @param refusal why it was refused, when it was
   * @param estimate the sizing that informed the decision
   */
  public record Submission(ExportJob job, String refusal, ExportEstimate estimate) {

    /** Whether the export was accepted. */
    public boolean accepted() {
      return job != null;
    }
  }

  /** Submits an already-authorized request. */
  public Submission submit(AuthenticatedUser user, ExportRequest request, String sourceIp) {
    ExportEstimate estimate = estimator.estimate(request);
    List<String> teams = teamsOf(request);

    QuotaDecision decision = quotas.admit(user.subject(), teams, request, estimate);
    if (!decision.admitted()) {
      audit.record(
          user.subject(),
          AuditAction.EXPORT_REFUSED,
          Map.of(
              "namespaces", request.namespaces(),
              "estimatedBytes", estimate.estimatedBytes(),
              "teams", teams,
              "reason", decision.reason()),
          sourceIp);
      metrics.submission("refused", "quota");
      return new Submission(null, decision.reason(), estimate);
    }

    WindowPlan plan = planner.plan(request.from(), request.to(), estimate.estimatedBytes());
    UUID id =
        jobs.create(
            new NewJob(
                request,
                estimate.selector(),
                user.subject(),
                user.name(),
                List.copyOf(user.groups()),
                teams,
                estimate.estimatedBytes(),
                decision.byteLimit(),
                plan.windowDuration().toSeconds()),
            plan.windows());

    audit.record(
        user.subject(),
        AuditAction.EXPORT_SUBMITTED,
        Map.of(
            "jobId", id.toString(),
            "namespaces", request.namespaces(),
            "selector", estimate.selector(),
            "estimatedBytes", estimate.estimatedBytes(),
            "byteLimit", decision.byteLimit(),
            "windows", plan.windowCount()),
        sourceIp);

    metrics.submission("accepted", "none");
    return new Submission(jobs.find(id).orElseThrow(), null, estimate);
  }

  /**
   * The teams that own the namespaces being exported.
   *
   * <p>Resolved once, at submission, and stored on the job. The budget is an
   * accounting question about the past, and re-deriving it later would let a
   * relabelled namespace quietly rewrite who spent what.
   */
  private List<String> teamsOf(ExportRequest request) {
    return request.namespaces().stream()
        .map(namespaces::find)
        .flatMap(java.util.Optional::stream)
        .map(NamespaceInfo::team)
        .distinct()
        .toList();
  }

  /** One of the caller's jobs. */
  public Optional<ExportJob> findFor(AuthenticatedUser user, UUID id) {
    return jobs.find(id).filter(job -> job.requestedBy().equals(user.subject()));
  }

  /** The caller's jobs, most recent first. */
  public List<ExportJob> listFor(AuthenticatedUser user, int limit) {
    return jobs.listFor(user.subject(), limit);
  }

  /** Asks one of the caller's jobs to stop. */
  public boolean cancel(AuthenticatedUser user, UUID id, String sourceIp) {
    boolean cancelled = jobs.requestCancel(id, user.subject());
    if (cancelled) {
      audit.record(
          user.subject(), AuditAction.EXPORT_CANCELLED, Map.of("jobId", id.toString()), sourceIp);
    }
    return cancelled;
  }
}
