package com.opentooling.loggate.jobs;

import com.opentooling.loggate.audit.AuditAction;
import com.opentooling.loggate.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Puts how each export ended on the audit trail: its files written, or not.
 *
 * <p>Submission is recorded when someone asks, but the files are made later by
 * a worker, and whether they came to exist at all is as much a part of who
 * took which logs as the download is. These are recorded against the person
 * who asked, with no address: no request of theirs caused them.
 */
public class ExportOutcomes {

  private final ExportJobRepository jobs;
  private final AuditService audit;

  public ExportOutcomes(ExportJobRepository jobs, AuditService audit) {
    this.jobs = jobs;
    this.audit = audit;
  }

  /** The export's files were written and published. */
  public void completed(UUID jobId) {
    jobs.find(jobId)
        .ifPresent(
            job -> {
              Map<String, Object> detail = describe(job);
              detail.put("files", jobs.listArtifacts(jobId, "PART").size());
              audit.record(job.requestedBy(), AuditAction.EXPORT_COMPLETED, jobId, detail, null);
            });
  }

  /** The export stopped without its files. */
  public void failed(UUID jobId, FailureCode code, String message) {
    jobs.find(jobId)
        .ifPresent(
            job -> {
              Map<String, Object> detail = describe(job);
              detail.put("reason", message == null ? code.name() : code.name() + ": " + message);
              audit.record(job.requestedBy(), AuditAction.EXPORT_FAILED, jobId, detail, null);
            });
  }

  private static Map<String, Object> describe(ExportJob job) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("namespaces", job.namespaces());
    detail.put("clusters", job.clusters());
    detail.put("bytes", job.bytesWritten());
    return detail;
  }
}
