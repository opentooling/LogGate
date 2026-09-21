package com.opentooling.loggate.export;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import com.opentooling.loggate.jobs.ExportJob;
import com.opentooling.loggate.jobs.ExportJobRepository;
import com.opentooling.loggate.jobs.NewJob;
import com.opentooling.loggate.authz.NamespaceAccess;
import com.opentooling.loggate.quota.BudgetHolder;
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
  private final NamespaceAccess access;
  private final AuditService audit;
  private final com.opentooling.loggate.observability.ExportMetrics metrics;

  public ExportService(
      ExportEstimator estimator,
      WindowPlanner planner,
      QuotaGuard quotas,
      ExportJobRepository jobs,
      NamespaceAccess access,
      AuditService audit,
      com.opentooling.loggate.observability.ExportMetrics metrics) {
    this.estimator = estimator;
    this.planner = planner;
    this.quotas = quotas;
    this.jobs = jobs;
    this.access = access;
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

  /**
   * What submitting this request would cost, and whether it would be allowed.
   *
   * <p>The admission decision comes from the same guard that enforces it at
   * submission, so the answer shown before pressing the button and the answer
   * given after it cannot drift apart. It is advice rather than enforcement:
   * concurrency moves, and a refusal here may have cleared by the time the
   * request is actually made.
   *
   * @param estimate what the export would cost
   * @param decision what the quota guard would say about it now
   */
  public record Preflight(ExportEstimate estimate, QuotaDecision decision) {}

  /** Sizes an already-authorized request and checks it against quota. */
  public Preflight preflight(AuthenticatedUser user, ExportRequest request) {
    ExportRequest scoped = access.scope(request);
    ExportEstimate estimate = estimator.estimate(scoped);
    return new Preflight(
        estimate,
        quotas.admit(user.subject(), access.chargedTo(user, scoped), scoped, estimate));
  }

  /** Submits an already-authorized request. */
  public Submission submit(AuthenticatedUser user, ExportRequest requested, String sourceIp) {
    // Scoped before anything else reads it, so the query that runs, the size
    // that is admitted and the job that is stored all describe the same thing.
    ExportRequest request = access.scope(requested);
    ExportEstimate estimate = estimator.estimate(request);
    List<BudgetHolder> charged = access.chargedTo(user, request);
    List<String> teams = charged.stream().map(BudgetHolder::id).toList();

    QuotaDecision decision = quotas.admit(user.subject(), charged, request, estimate);
    if (!decision.admitted()) {
      audit.record(
          user.subject(),
          AuditAction.EXPORT_REFUSED,
          Map.of(
              "namespaces", request.namespaces(),
              "clusters", request.clusters(),
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
            "clusters", request.clusters(),
            "selector", estimate.selector(),
            "estimatedBytes", estimate.estimatedBytes(),
            "byteLimit", decision.byteLimit(),
            "windows", plan.windowCount()),
        sourceIp);

    metrics.submission("accepted", "none");
    return new Submission(jobs.find(id).orElseThrow(), null, estimate);
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
