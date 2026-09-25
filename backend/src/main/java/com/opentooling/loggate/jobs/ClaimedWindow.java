package com.opentooling.loggate.jobs;

import java.time.Instant;
import java.util.UUID;

/**
 * A window a worker has taken a lease on, with everything needed to run it.
 *
 * @param jobId the owning job
 * @param index the window's position, which is also its part's sort key
 * @param from start of the window, inclusive
 * @param to end of the window, exclusive
 * @param selector the job's generated LogQL selector
 * @param byteLimit the job's byte cap
 * @param jobBytesWritten bytes the job had already written when this was claimed
 * @param attempts how many times this window has been attempted, including now
 */
public record ClaimedWindow(
    UUID jobId,
    int index,
    Instant from,
    Instant to,
    String selector,
    long byteLimit,
    long jobBytesWritten,
    int attempts,
    com.opentooling.loggate.export.OutputFormat format) {}
