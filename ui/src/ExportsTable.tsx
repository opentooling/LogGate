import { Fragment, useEffect, useMemo, useState } from "react";
import { api, ApiError, type Download, type ExportJob } from "./api";
import { QueryChip } from "./Chips";
import { serverNow } from "./clock";
import {
  advice,
  explainFailure,
  formatApprox,
  formatBytes,
  formatCount,
  formatRemaining,
  formatSpan,
  remainingSeconds,
} from "./format";
import { failureLabel } from "./charts";
import { ACTIVE, inTab, matchesJob, type Tab } from "./jobs";

const TABS: { value: Tab; label: string }[] = [
  { value: "active", label: "Active" },
  { value: "finished", label: "Finished" },
  { value: "all", label: "All" },
];

/**
 * Every export the caller has made, as a table: what is running on its own
 * tab, finished ones searchable, and each with the one action it needs.
 */
export function ExportsTable({
  jobs,
  onChanged,
  onRunAgain,
  onError,
}: {
  jobs: ExportJob[];
  onChanged: () => void;
  onRunAgain: (job: ExportJob) => void;
  onError: (message: string) => void;
}) {
  const running = jobs.filter((job) => ACTIVE.has(job.state)).length;
  // Opens on what is running, if anything is; otherwise on what is finished.
  const [chosenTab, setTab] = useState<Tab | null>(null);
  const tab = chosenTab ?? (running > 0 ? "active" : "finished");
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const shown = useMemo(
    () => inTab(jobs, tab).filter((job) => matchesJob(job, search)),
    [jobs, tab, search],
  );
  const counts = { active: running, finished: jobs.length - running, all: jobs.length };

  return (
    <section className="panel exports" aria-label="Exports" data-testid="exports">
      <div className="exports-head">
        <h2>Exports</h2>
        <div className="segmented" role="group" aria-label="Show">
          {TABS.map((t) => (
            <button key={t.value} type="button" aria-pressed={tab === t.value} onClick={() => setTab(t.value)}>
              {t.label} <span className="count">{counts[t.value]}</span>
            </button>
          ))}
        </div>
        <span className="run-spacer" />
        <input
          className="exports-search"
          type="search"
          aria-label="Search exports"
          placeholder="Search exports"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {jobs.length === 0 ? (
        <p className="quiet exports-empty">
          Nothing yet. Exports you start appear here, and keep running if you close this page.
        </p>
      ) : shown.length === 0 ? (
        <p className="quiet exports-empty">
          {search ? `No export matches “${search}”.` : tab === "active" ? "Nothing is running." : "No finished exports."}
        </p>
      ) : (
        <div className="table-wrap">
          <table className="jobs-table">
            <thead>
              <tr>
                <th scope="col">Status</th>
                <th scope="col">Scope</th>
                <th scope="col">Range</th>
                <th scope="col" className="num-col">Size</th>
                <th scope="col">Progress</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {shown.map((job) => (
                <Fragment key={job.id}>
                  <Row
                    job={job}
                    expanded={open === job.id}
                    onToggleFiles={() => setOpen((was) => (was === job.id ? null : job.id))}
                    onChanged={onChanged}
                    onRunAgain={() => onRunAgain(job)}
                    onError={onError}
                  />
                  {open === job.id && <FilesRow job={job} />}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

const STATUS: Record<string, { label: string; tone: string }> = {
  QUEUED: { label: "Queued", tone: "run" },
  PLANNED: { label: "Planned", tone: "run" },
  RUNNING: { label: "Running", tone: "run" },
  FINALIZING: { label: "Finalizing", tone: "run" },
  READY: { label: "Ready", tone: "ok" },
  EXPIRED: { label: "Expired", tone: "off" },
  CANCELLED: { label: "Cancelled", tone: "off" },
  FAILED: { label: "Failed", tone: "bad" },
};

function secondsSince(iso: string): number {
  return Math.max(0, Math.round((serverNow() - new Date(iso).getTime()) / 1000));
}

/** Seconds until an ISO timestamp, or null when it has passed or is absent. */
export function secondsUntil(iso: string | null, now: number = serverNow()): number | null {
  if (!iso) return null;
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  return seconds > 0 ? seconds : null;
}

function Row({
  job,
  expanded,
  onToggleFiles,
  onChanged,
  onRunAgain,
  onError,
}: {
  job: ExportJob;
  expanded: boolean;
  onToggleFiles: () => void;
  onChanged: () => void;
  onRunAgain: () => void;
  onError: (message: string) => void;
}) {
  const status = STATUS[job.state] ?? { label: job.state, tone: "off" };
  const active = ACTIVE.has(job.state);

  async function cancel() {
    try {
      await api.cancel(job.id);
      onChanged();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <tr data-testid="job" data-state={job.state} className={expanded ? "row-open" : undefined}>
      <td>
        <span className={`state st st-${status.tone}`}>{status.label}</span>
      </td>
      <td className="scope-cell">
        <span className="scope-names">
          {job.namespaces.length > 0 ? job.namespaces.join(", ") : "every namespace"}
        </span>
        {job.clusters.map((cluster) => (
          <span key={cluster} className="tag">
            {cluster}
          </span>
        ))}
        {job.format === "RAW" && <span className="tag">raw lines</span>}
      </td>
      <td data-label="Range" className="num">
        {formatSpan(job.from, job.to)}
      </td>
      <td data-label="Size" className="num num-col">
        {job.bytesWritten > 0 ? formatBytes(job.bytesWritten) : active ? `≈ ${formatBytes(job.estimatedBytes)}` : "–"}
      </td>
      <td className="progress-cell">
        <ProgressCell job={job} />
      </td>
      <td className="actions-cell">
        {active ? (
          <button type="button" className="text-button" onClick={cancel} disabled={job.cancelRequested}>
            {job.cancelRequested ? "Stopping…" : "Cancel"}
          </button>
        ) : job.state === "READY" && job.downloadable && job.entriesWritten > 0 ? (
          <DownloadMenu job={job} onFiles={onToggleFiles} filesOpen={expanded} />
        ) : (
          <button type="button" className="text-button" onClick={onRunAgain}>
            Run again
          </button>
        )}
      </td>
    </tr>
  );
}

/** How far along, or how it ended, in a line. */
function ProgressCell({ job }: { job: ExportJob }) {
  if (ACTIVE.has(job.state)) {
    const percent = Math.round(job.progress * 100);
    const left = remainingSeconds(job.windowsDone, job.windowsTotal, secondsSince(job.createdAt));
    return (
      <div>
        <div className="progress" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
          <div className="bar" style={{ width: `${percent}%` }} />
        </div>
        <span className="quiet small-text progress-detail" aria-live="polite">
          {job.windowsDone} of {job.windowsTotal}
          {left !== null && <> · {formatRemaining(left)}</>}
        </span>
      </div>
    );
  }
  switch (job.state) {
    case "READY":
      if (job.entriesWritten === 0) return <span className="quiet" data-testid="empty">No log lines matched</span>;
      if (!job.downloadable)
        return (
          <span className="quiet" data-testid="not-downloadable" title="You no longer have access to all of its namespaces, or it was made in another access mode.">
            No longer yours to download
          </span>
        );
      return <span className="quiet num">{formatCount(job.entriesWritten)} entries</span>;
    case "FAILED":
      // The short reason in the row; the full explanation on hover.
      return (
        <span className="failure" title={explainFailure(job.failureCode, job.failureDetail)}>
          {failureLabel(job.failureCode ?? "UNKNOWN")}
        </span>
      );
    case "CANCELLED":
      return <span className="quiet">Partial files deleted</span>;
    case "EXPIRED":
      return <span className="quiet">Files deleted</span>;
    default:
      return null;
  }
}

/** The ways to take a finished export, one click from the row. */
function DownloadMenu({ job, onFiles, filesOpen }: { job: ExportJob; onFiles: () => void; filesOpen: boolean }) {
  const how = advice(job.bytesWritten);
  const expiring = secondsUntil(job.expiresAt);
  return (
    <div className="download-menu">
      {/* Floating, because the table scrolls sideways on narrow screens and
          so clips anything that opens inside it. */}
      <QueryChip
        label="Download"
        value="Download"
        testId={`download-${job.id.slice(0, 8)}`}
        panelWidth="17rem"
        floating
      >
        {(close) => (
          <div className="menu">
            <a
              className={how.archive ? "menu-item menu-primary" : "menu-item"}
              href={`/api/exports/${job.id}/archive.zip`}
              onClick={close}
            >
              Download .zip
            </a>
            <a
              className={how.archive ? "menu-item" : "menu-item menu-primary"}
              href={`/api/exports/${job.id}/download.sh`}
              onClick={close}
            >
              Download script
            </a>
            <button
              type="button"
              className="menu-item"
              onClick={() => {
                onFiles();
                close();
              }}
            >
              {filesOpen ? "Hide the files" : "The files, individually"}
            </button>
            <p className="quiet small-text menu-note">
              {how.reason}
              {expiring !== null && <> Deleted in {formatApprox(expiring)}.</>}
            </p>
          </div>
        )}
      </QueryChip>
    </div>
  );
}

/**
 * The files of one export, each with its own link. Asked for only when
 * opened: issuing the links hands the data over, and is audited as such.
 */
function FilesRow({ job }: { job: ExportJob }) {
  const [downloads, setDownloads] = useState<Download[] | null>(null);
  useEffect(() => {
    let current = true;
    api
      .downloads(job.id)
      .then((files) => current && setDownloads(files))
      .catch(() => current && setDownloads([]));
    return () => {
      current = false;
    };
  }, [job.id]);
  return (
    <tr className="files-row">
      <td colSpan={6}>
        {downloads === null ? (
          <p className="quiet small-text">Asking for links…</p>
        ) : (
          <ul className="files">
            {downloads.map((file) => (
              <li key={file.key}>
                <a href={file.url}>{file.name}</a>
                <span className="quiet num">{formatBytes(file.sizeBytes)}</span>
              </li>
            ))}
          </ul>
        )}
        <p className="quiet small-text">Links expire shortly. The manifest lists a checksum for every file.</p>
      </td>
    </tr>
  );
}
