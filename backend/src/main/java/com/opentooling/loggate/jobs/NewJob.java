package com.opentooling.loggate.jobs;

import com.opentooling.loggate.export.ExportRequest;
import java.util.List;

/**
 * Everything needed to record a newly admitted export.
 *
 * @param request the validated request
 * @param selector the generated selector
 * @param requestedBy OIDC subject of the caller
 * @param requestedByName display name
 * @param groups the caller's groups, snapshotted for the audit trail
 * @param estimatedBytes what sizing predicted
 * @param byteLimit the cap the job is admitted under
 * @param windowSeconds the planned window duration
 */
public record NewJob(
    ExportRequest request,
    String selector,
    String requestedBy,
    String requestedByName,
    List<String> groups,
    long estimatedBytes,
    long byteLimit,
    long windowSeconds) {}
