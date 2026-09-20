import { useCallback, useEffect, useState } from "react";
import {
  api,
  ApiError,
  formatBytes,
  formatDuration,
  type Download,
  type Estimate,
  type ExportJob,
  type ExportRequest,
  type Me,
} from "./api";

/** States that are still moving, and therefore worth polling. */
const ACTIVE_STATES = new Set(["QUEUED", "PLANNED", "RUNNING", "FINALIZING"]);

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setJobs(await api.list());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    api.me().then(setMe).catch(() => {});
    refresh();
  }, [refresh]);

  // Poll only while something is actually running, so an idle page is quiet.
  useEffect(() => {
    if (!jobs.some((job) => ACTIVE_STATES.has(job.state))) return;
    const timer = setInterval(refresh, 2000);
    return () => clearInterval(timer);
  }, [jobs, refresh]);

  return (
    <div className="page">
      <header>
        <h1>LogGate</h1>
        <p className="tagline">Bulk log export, for when a dashboard is the wrong tool.</p>
        {me && (
          <div className="who">
            <span>{me.name}</span>
            <a href="/logout">Sign out</a>
          </div>
        )}
      </header>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {me && me.namespaces.length === 0 ? (
        <section className="card empty">
          <h2>No namespaces</h2>
          <p>
            You are not a member of any group that owns a labelled namespace, so there is nothing
            you can export. Ask the team that owns the namespace to add you to its group.
          </p>
        </section>
      ) : (
        me && <NewExport me={me} onSubmitted={refresh} onError={setError} />
      )}

      <Exports jobs={jobs} onChanged={refresh} onError={setError} />
    </div>
  );
}

function isoLocal(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

function NewExport({
  me,
  onSubmitted,
  onError,
}: {
  me: Me;
  onSubmitted: () => void;
  onError: (message: string) => void;
}) {
  const [namespaces, setNamespaces] = useState<string[]>([me.namespaces[0]?.name].filter(Boolean) as string[]);
  const [podPattern, setPodPattern] = useState("");
  const [lineFilter, setLineFilter] = useState("");
  const [from, setFrom] = useState(isoLocal(new Date(Date.now() - 60 * 60 * 1000)));
  const [to, setTo] = useState(isoLocal(new Date()));
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  const [busy, setBusy] = useState(false);

  function request(): ExportRequest {
    return {
      namespaces,
      podPattern: podPattern || undefined,
      lineFilter: lineFilter || undefined,
      from: new Date(from).toISOString(),
      to: new Date(to).toISOString(),
    };
  }

  async function run(action: "estimate" | "submit") {
    setBusy(true);
    onError("");
    try {
      if (action === "estimate") {
        setEstimate(await api.estimate(request()));
      } else {
        await api.submit(request());
        setEstimate(null);
        onSubmitted();
      }
    } catch (e) {
      onError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>New export</h2>

      <label>
        Namespaces
        <select
          multiple
          size={Math.min(4, Math.max(2, me.namespaces.length))}
          value={namespaces}
          onChange={(e) =>
            setNamespaces(Array.from(e.target.selectedOptions, (option) => option.value))
          }
        >
          {me.namespaces.map((namespace) => (
            <option key={namespace.name} value={namespace.name}>
              {namespace.name} — {namespace.team}
            </option>
          ))}
        </select>
      </label>

      <div className="row">
        <label>
          Pods
          <input
            value={podPattern}
            placeholder="api-*"
            onChange={(e) => setPodPattern(e.target.value)}
          />
          <small>A glob, not a regex. Leave empty for every pod.</small>
        </label>
        <label>
          Line contains
          <input
            value={lineFilter}
            placeholder="timeout"
            onChange={(e) => setLineFilter(e.target.value)}
          />
          <small>Plain text, matched literally.</small>
        </label>
      </div>

      <div className="row">
        <label>
          From
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          To
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
      </div>

      {estimate && (
        <div className="estimate">
          <strong>{formatBytes(estimate.estimatedBytes)}</strong> across{" "}
          {estimate.windowCount} window{estimate.windowCount === 1 ? "" : "s"} of{" "}
          {formatDuration(estimate.windowSeconds)}.
          <code>{estimate.selector}</code>
        </div>
      )}

      <div className="actions">
        <button type="button" onClick={() => run("estimate")} disabled={busy || !namespaces.length}>
          Estimate first
        </button>
        <button
          type="button"
          className="primary"
          onClick={() => run("submit")}
          disabled={busy || !namespaces.length}
        >
          Start export
        </button>
      </div>
    </section>
  );
}

function Exports({
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
        <p className="quiet">Nothing yet.</p>
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

  const active = ACTIVE_STATES.has(job.state);

  return (
    <li className="job" data-state={job.state} data-testid="job">
      <div className="job-head">
        <span className={`state state-${job.state.toLowerCase()}`}>{job.state}</span>
        <span className="namespaces">{job.namespaces.join(", ")}</span>
        <span className="quiet">
          {new Date(job.from).toLocaleString()} → {new Date(job.to).toLocaleString()}
        </span>
        {active && (
          <button type="button" onClick={cancel} disabled={job.cancelRequested}>
            {job.cancelRequested ? "Stopping…" : "Cancel"}
          </button>
        )}
      </div>

      {active && (
        <div className="progress" title={`${job.windowsDone} of ${job.windowsTotal} windows`}>
          <div className="bar" style={{ width: `${Math.round(job.progress * 100)}%` }} />
          <span>
            {job.windowsDone} / {job.windowsTotal} windows · {formatBytes(job.bytesWritten)}
          </span>
        </div>
      )}

      {job.state === "FAILED" && (
        <p className="error">
          {job.failureCode}: {job.failureDetail}
        </p>
      )}

      {job.state === "READY" && (
        <div className="downloads">
          <p className="quiet">
            {job.entriesWritten.toLocaleString()} entries · {formatBytes(job.bytesWritten)}{" "}
            uncompressed
          </p>
          <div className="actions">
            <a className="button" href={`/api/exports/${job.id}/download.sh`}>
              Download script
            </a>
            <a className="button" href={`/api/exports/${job.id}/archive.zip`}>
              Download .zip
            </a>
          </div>
          {downloads && downloads.length > 0 && (
            <details>
              <summary>{downloads.length} files</summary>
              <ul className="files">
                {downloads.map((file) => (
                  <li key={file.key}>
                    <a href={file.url}>{file.name}</a>
                    <span className="quiet">{formatBytes(file.sizeBytes)}</span>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </div>
      )}
    </li>
  );
}
