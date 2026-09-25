import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError, type DownloadAction, type DownloadEvent } from "./api";
import { formatBytes, formatCount } from "./format";

/** What each way of taking an export means, in the words of the table. */
export const HOW: Record<DownloadAction, { label: string; detail: string }> = {
  ARCHIVE_DOWNLOADED: {
    label: "Downloaded .zip",
    detail: "Streamed through LogGate as one archive.",
  },
  DOWNLOAD_SCRIPT_ISSUED: {
    label: "Took the script",
    detail: "A script holding a link to every file. The files come from storage directly.",
  },
  DOWNLOAD_LINKS_ISSUED: {
    label: "Opened the file links",
    detail: "Links to each file. The files come from storage directly.",
  },
};

/**
 * Every download, for administrators: who took which export's data, how,
 * when and from where.
 *
 * <p>Newest first, a page at a time. Links and the script are recorded when
 * they are handed out, because the files themselves are fetched from object
 * storage where LogGate never sees them; the table says which is which rather
 * than calling both a download.
 */
export function Audit({ onError }: { onError: (message: string) => void }) {
  const [events, setEvents] = useState<DownloadEvent[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [totals, setTotals] = useState<Partial<Record<DownloadAction, number>>>({});
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("");

  const load = useCallback(
    (before: number | null) => {
      setLoading(true);
      api
        .downloadAudit(before)
        .then((audit) => {
          setEvents((shown) => (before === null ? audit.page.events : [...shown, ...audit.page.events]));
          setNext(audit.page.next);
          setTotals(audit.totals);
        })
        .catch((e) => onError(e instanceof ApiError ? e.message : String(e)))
        .finally(() => setLoading(false));
    },
    [onError],
  );

  useEffect(() => load(null), [load]);

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return events;
    return events.filter((event) =>
      [event.name, event.subject, event.jobId ?? "", event.sourceIp ?? "", HOW[event.action].label,
        ...event.namespaces, ...event.clusters]
        .some((text) => text.toLowerCase().includes(needle)),
    );
  }, [events, filter]);

  const total = Object.values(totals).reduce((sum, n) => sum + (n ?? 0), 0);

  return (
    <section className="card audit" data-testid="audit">
      <div className="activity-head">
        <div>
          <h2>Downloads</h2>
          <p className="quiet activity-scope">
            Every time an export&apos;s data was handed to someone, newest first.
          </p>
        </div>
        <button type="button" onClick={() => load(null)} disabled={loading}>
          Refresh
        </button>
      </div>

      <div className="tiles audit-tiles">
        <div className="tile" data-testid="tile">
          <span className="tile-label">All downloads</span>
          <strong className="tile-value">{formatCount(total)}</strong>
        </div>
        {(Object.keys(HOW) as DownloadAction[]).map((action) => (
          <div key={action} className="tile" data-testid="tile">
            <span className="tile-label">{HOW[action].label}</span>
            <strong className="tile-value">{formatCount(totals[action] ?? 0)}</strong>
          </div>
        ))}
      </div>

      <input
        className="picker-filter audit-filter"
        aria-label="Filter downloads"
        placeholder="Filter by person, namespace, cluster, export or address"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
      />

      {events.length === 0 && !loading ? (
        <p className="quiet chart-empty">Nothing has been downloaded yet.</p>
      ) : (
        <div className="table-wrap">
          <table className="audit-table" data-testid="audit-table">
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">How</th>
                <th scope="col">What</th>
                <th scope="col" className="num">Size</th>
                <th scope="col">From</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((event) => (
                <tr key={event.id} data-testid="audit-row">
                  <td>
                    <time dateTime={event.at} title={new Date(event.at).toISOString()}>
                      {new Date(event.at).toLocaleString([], {
                        month: "short",
                        day: "numeric",
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                    </time>
                  </td>
                  <td title={event.subject}>{event.name}</td>
                  <td>
                    <span className={`how how-${event.action.toLowerCase()}`} title={HOW[event.action].detail}>
                      {HOW[event.action].label}
                    </span>
                    {event.files !== null && <small>{formatCount(event.files)} files</small>}
                  </td>
                  <td>
                    {event.clusters.map((cluster) => (
                      <span key={cluster} className="cluster-chip">
                        {cluster}
                      </span>
                    ))}
                    <span>{event.namespaces.length ? event.namespaces.join(", ") : "every namespace"}</span>
                    {event.jobId && <small title={event.jobId}>export {event.jobId.slice(0, 8)}</small>}
                  </td>
                  <td className="num">{event.bytes === null ? "–" : formatBytes(event.bytes)}</td>
                  <td className="quiet">{event.sourceIp ?? "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {shown.length === 0 && <p className="quiet">No download matches “{filter}”.</p>}
        </div>
      )}

      <div className="actions">
        {next !== null && (
          <button type="button" onClick={() => load(next)} disabled={loading}>
            {loading ? "Loading…" : "Show older"}
          </button>
        )}
        {filter && next !== null && (
          <small>The filter applies to what is loaded; show older ones to search further back.</small>
        )}
      </div>
    </section>
  );
}
