import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError, type AuditAction, type AuditEvent, type AuditKind } from "./api";
import { formatBytes, formatCount } from "./format";

/** What each action means, in the words of the table. */
export const HOW: Record<AuditAction, { label: string; detail: string; tone?: "good" | "bad" | "strong" }> = {
  EXPORT_SUBMITTED: {
    label: "Asked for an export",
    detail: "An export was accepted and queued. Its files are written afterwards.",
  },
  EXPORT_COMPLETED: {
    label: "Export files made",
    detail: "Every part was written and the export became ready to download.",
    tone: "good",
  },
  EXPORT_FAILED: {
    label: "Export failed",
    detail: "The export stopped without its files: over its byte cap, or a source that kept failing.",
    tone: "bad",
  },
  EXPORT_REFUSED: {
    label: "Export refused",
    detail: "Refused by a quota before anything ran.",
    tone: "bad",
  },
  EXPORT_CANCELLED: {
    label: "Export cancelled",
    detail: "Asked to stop. Anything written so far is deleted.",
  },
  NAMESPACE_ACCESS_DENIED: {
    label: "Access denied",
    detail: "Asked for logs from namespaces or clusters they may not export.",
    tone: "bad",
  },
  ARCHIVE_DOWNLOADED: {
    label: "Downloaded .zip",
    detail: "Streamed through LogGate as one archive.",
    tone: "strong",
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

const KINDS: { value: AuditKind; label: string; empty: string }[] = [
  { value: "all", label: "All", empty: "Nothing has been recorded yet." },
  { value: "exports", label: "Exports", empty: "No export has been asked for yet." },
  { value: "downloads", label: "Downloads", empty: "Nothing has been downloaded yet." },
  { value: "denials", label: "Denied", empty: "Nobody has been denied access." },
];

const sum = (totals: Partial<Record<AuditAction, number>>, actions: AuditAction[]) =>
  actions.reduce((n, action) => n + (totals[action] ?? 0), 0);

/**
 * The audit trail, for administrators: who asked for which logs, whether the
 * export's files were made, who took them and how, when and from where.
 *
 * <p>Newest first, a page at a time. Links and the script are recorded when
 * they are handed out, because the files themselves are fetched from object
 * storage where LogGate never sees them; the table says which is which rather
 * than calling both a download.
 */
export function Audit({ onError }: { onError: (message: string) => void }) {
  const [kind, setKind] = useState<AuditKind>("all");
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [totals, setTotals] = useState<Partial<Record<AuditAction, number>>>({});
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState("");

  const load = useCallback(
    (before: number | null) => {
      setLoading(true);
      api
        .audit(kind, before)
        .then((audit) => {
          setEvents((shown) => (before === null ? audit.page.events : [...shown, ...audit.page.events]));
          setNext(audit.page.next);
          setTotals(audit.totals);
        })
        .catch((e) => onError(e instanceof ApiError ? e.message : String(e)))
        .finally(() => setLoading(false));
    },
    [kind, onError],
  );

  useEffect(() => load(null), [load]);

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return events;
    return events.filter((event) =>
      [event.name, event.subject, event.jobId ?? "", event.sourceIp ?? "", event.note ?? "",
        HOW[event.action].label, ...event.namespaces, ...event.clusters]
        .some((text) => text.toLowerCase().includes(needle)),
    );
  }, [events, filter]);

  const tiles: { label: string; value: number }[] = [
    { label: "Exports asked for", value: sum(totals, ["EXPORT_SUBMITTED"]) },
    { label: "Export files made", value: sum(totals, ["EXPORT_COMPLETED"]) },
    { label: "Downloads", value: sum(totals, ["ARCHIVE_DOWNLOADED", "DOWNLOAD_SCRIPT_ISSUED", "DOWNLOAD_LINKS_ISSUED"]) },
    { label: "Refused or denied", value: sum(totals, ["EXPORT_REFUSED", "NAMESPACE_ACCESS_DENIED"]) },
  ];

  return (
    <section className="card audit" data-testid="audit">
      <div className="activity-head">
        <div>
          <h2>Audit trail</h2>
          <p className="quiet activity-scope">
            Who asked for which logs, whether the files were made, and who took them. Newest first.
          </p>
        </div>
        <button type="button" onClick={() => load(null)} disabled={loading}>
          Refresh
        </button>
      </div>

      <div className="tiles audit-tiles">
        {tiles.map((tile) => (
          <div key={tile.label} className="tile" data-testid="tile">
            <span className="tile-label">{tile.label}</span>
            <strong className="tile-value">{formatCount(tile.value)}</strong>
          </div>
        ))}
      </div>

      <div className="audit-controls">
        <div className="segmented" role="group" aria-label="Show">
          {KINDS.map((k) => (
            <button key={k.value} type="button" aria-pressed={kind === k.value} onClick={() => setKind(k.value)}>
              {k.label}
            </button>
          ))}
        </div>
        <input
          className="picker-filter audit-filter"
          aria-label="Filter events"
          placeholder="Filter by person, namespace, cluster, export, address or reason"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
      </div>

      {events.length === 0 && !loading ? (
        <p className="quiet chart-empty">{KINDS.find((k) => k.value === kind)!.empty}</p>
      ) : (
        <div className="table-wrap">
          <table className="audit-table" data-testid="audit-table">
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">What happened</th>
                <th scope="col">Logs</th>
                <th scope="col" className="num">Size</th>
                <th scope="col">From</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((event) => {
                const how = HOW[event.action];
                return (
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
                      <span className={how.tone ? `how how-${how.tone}` : "how"} title={how.detail}>
                        {how.label}
                      </span>
                      {event.files !== null && <small>{formatCount(event.files)} files</small>}
                      {event.note && <small className="audit-note">{event.note}</small>}
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
                    <td className="num">
                      {event.bytes !== null ? (
                        formatBytes(event.bytes)
                      ) : event.estimatedBytes !== null ? (
                        <span title="Estimated when it was asked for">~{formatBytes(event.estimatedBytes)}</span>
                      ) : (
                        "–"
                      )}
                    </td>
                    <td className="quiet">{event.sourceIp ?? (event.jobId ? "LogGate" : "–")}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {shown.length === 0 && <p className="quiet">Nothing matches “{filter}”.</p>}
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
