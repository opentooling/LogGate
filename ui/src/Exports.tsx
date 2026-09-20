import { useEffect, useState } from "react";
import { ACTIVE_STATES } from "./App";
import { api, ApiError, type Download, type ExportJob } from "./api";
import { advice, explainFailure, formatBytes, formatCount, formatDuration } from "./format";

export function Exports({
  jobs,
  onChanged,
  onError,
}: {
  jobs: ExportJob[];
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  return (
    <section className="card">
      <h2>Your exports</h2>
      {jobs.length === 0 ? (
        <p className="quiet">
          Nothing yet. Exports you start appear here and keep running if you close this page.
        </p>
      ) : (
        <ul className="jobs">
          {jobs.map((job) => (
            <Job key={job.id} job={job} onChanged={onChanged} onError={onError} />
          ))}
        </ul>
      )}
    </section>
  );
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

function Progress({ job }: { job: ExportJob }) {
  const percent = Math.round(job.progress * 100);
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
      </p>
    </div>
  );
}

function Ready({ job, downloads }: { job: ExportJob; downloads: Download[] | null }) {
  const how = advice(job.bytesWritten);
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
