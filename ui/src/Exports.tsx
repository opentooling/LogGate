import { useEffect, useState } from "react";
import { ACTIVE_STATES } from "./App";
import { api, ApiError, type Download, type ExportJob } from "./api";
import { serverNow } from "./clock";
import {
  advice,
  explainFailure,
  formatAgo,
  formatApprox,
  formatBytes,
  formatCount,
  formatDuration,
  formatRate,
  formatRemaining,
  remainingSeconds,
} from "./format";

/**
 * How many exports are shown before the list is folded.
 *
 * <p>Someone who exports regularly accumulates dozens, and a page that opens
 * on all of them buries the one they just started under a month of history.
 */
const SHOWN = 8;

export function Exports({
  jobs,
  onChanged,
  onError,
}: {
  jobs: ExportJob[];
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [showAll, setShowAll] = useState(false);
  const folded = !showAll && jobs.length > SHOWN;
  const shown = folded ? jobs.slice(0, SHOWN) : jobs;

  return (
    <section className="card">
      <h2>Your exports</h2>
      {jobs.length === 0 ? (
        <p className="quiet">
          Nothing yet. Exports you start appear here and keep running if you close this page.
        </p>
      ) : (
        <>
          <ul className="jobs">
            {shown.map((job) => (
              <Job key={job.id} job={job} onChanged={onChanged} onError={onError} />
            ))}
          </ul>
          {folded && (
            <div className="actions">
              <button type="button" className="ghost" onClick={() => setShowAll(true)}>
                Show {jobs.length - SHOWN} older
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** Seconds since an ISO timestamp, never negative. */
function secondsSince(iso: string, now: number = serverNow()): number {
  return Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
}

/** Seconds until an ISO timestamp, or null when it has passed or is absent. */
export function secondsUntil(iso: string | null, now: number = serverNow()): number | null {
  if (!iso) return null;
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  return seconds > 0 ? seconds : null;
}

function Job({
  job,
  onChanged,
  onError,
}: {
  job: ExportJob;
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [downloads, setDownloads] = useState<Download[] | null>(null);
  const active = ACTIVE_STATES.has(job.state);

  useEffect(() => {
    if (job.state !== "READY") {
      setDownloads(null);
      return;
    }
    api.downloads(job.id).then(setDownloads).catch(() => setDownloads([]));
  }, [job.id, job.state]);

  async function cancel() {
    try {
      await api.cancel(job.id);
      onChanged();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : String(e));
    }
  }

  const rangeSeconds = Math.round(
    (new Date(job.to).getTime() - new Date(job.from).getTime()) / 1000,
  );

  return (
    <li className="job" data-state={job.state} data-testid="job">
      <div className="job-head">
        <span className={`state state-${job.state.toLowerCase()}`}>{job.state}</span>
        <span className="namespaces">{job.namespaces.join(", ")}</span>
        <span className="quiet range">
          {formatDuration(rangeSeconds)} to {new Date(job.to).toLocaleString()}
        </span>
        {active && (
          <button type="button" className="ghost" onClick={cancel} disabled={job.cancelRequested}>
            {job.cancelRequested ? "Stopping…" : "Cancel"}
          </button>
        )}
      </div>

      <p className="quiet job-meta">Submitted {formatAgo(secondsSince(job.createdAt))}</p>

      {active && <Progress job={job} />}

      {job.state === "FAILED" && (
        <p className="field-error">{explainFailure(job.failureCode, job.failureDetail)}</p>
      )}

      {job.state === "CANCELLED" && (
        <p className="quiet">Cancelled. Its partial files were deleted.</p>
      )}

      {job.state === "EXPIRED" && (
        <p className="quiet">The files have been deleted. Run the export again if you still need it.</p>
      )}

      {job.state === "READY" && <Ready job={job} downloads={downloads} />}
    </li>
  );
}

/**
 * Progress, and the two questions that follow it: how long, and how fast.
 *
 * <p>A bar alone tells someone an export is not finished. Whether to wait for
 * it or come back tomorrow needs a number.
 */
function Progress({ job }: { job: ExportJob }) {
  const percent = Math.round(job.progress * 100);
  const elapsed = secondsSince(job.createdAt);
  const left = remainingSeconds(job.windowsDone, job.windowsTotal, elapsed);
  const rate = formatRate(job.bytesWritten, elapsed);

  return (
    <div className="progress-wrap">
      <div
        className="progress"
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div className="bar" style={{ width: `${percent}%` }} />
      </div>
      <p className="quiet progress-detail" aria-live="polite">
        {job.windowsDone} of {job.windowsTotal} windows
        {job.entriesWritten > 0 && <> · {formatCount(job.entriesWritten)} entries</>}
        {job.bytesWritten > 0 && <> · {formatBytes(job.bytesWritten)}</>}
        {rate && <> · {rate}</>}
        {left !== null && <> · {formatRemaining(left)}</>}
      </p>
    </div>
  );
}

function Ready({ job, downloads }: { job: ExportJob; downloads: Download[] | null }) {
  const how = advice(job.bytesWritten);
  const expiring = secondsUntil(job.expiresAt);

  // An export that found nothing is a finding, not a file. Offering to download
  // an empty archive would hide the one thing worth knowing.
  if (job.entriesWritten === 0) {
    return (
      <div className="downloads" data-testid="empty">
        <p className="summary">No log lines matched</p>
        <p className="quiet">
          Nothing was logged in these namespaces over this range
          {job.selector.includes("pod=~") && ", by pods matching your pattern"}
          {job.selector.includes("|=") && ", containing your text"}. Check the time range and
          filters, then run it again.
        </p>
      </div>
    );
  }

  return (
    <div className="downloads">
      <p className="summary">
        {formatCount(job.entriesWritten)} entries · {formatBytes(job.bytesWritten)} uncompressed
      </p>
      <p className="quiet">{how.reason}</p>
      <div className="actions">
        <a
          className={how.archive ? "button" : "button primary"}
          href={`/api/exports/${job.id}/download.sh`}
        >
          Download script
        </a>
        <a
          className={how.archive ? "button primary" : "button"}
          href={`/api/exports/${job.id}/archive.zip`}
        >
          Download .zip
        </a>
      </div>
      {expiring !== null && (
        <p className="quiet job-meta" data-testid="expiry">
          These files are deleted in {formatApprox(expiring)}.
        </p>
      )}
      {downloads && downloads.length > 0 && (
        <details>
          <summary>{downloads.length} files, individually</summary>
          <ul className="files">
            {downloads.map((file) => (
              <li key={file.key}>
                <a href={file.url}>{file.name}</a>
                <span className="quiet">{formatBytes(file.sizeBytes)}</span>
              </li>
            ))}
          </ul>
          <p className="quiet">
            Links expire shortly. The manifest lists a checksum for every file.
          </p>
        </details>
      )}
    </div>
  );
}
