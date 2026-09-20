import { useMemo, useState } from "react";
import { api, ApiError, type Estimate, type ExportRequest, type Me } from "./api";
import { formatBytes, formatDuration } from "./format";
import { PRESETS, durationSeconds, rangeFor, toLocalInput } from "./ranges";

export function NewExport({
  me,
  onSubmitted,
  onError,
}: {
  me: Me;
  onSubmitted: () => void;
  onError: (message: string) => void;
}) {
  const [namespaces, setNamespaces] = useState<string[]>([me.namespaces[0]!.name]);
  const [podPattern, setPodPattern] = useState("");
  const [lineFilter, setLineFilter] = useState("");
  const initial = rangeFor(PRESETS[0]!);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  const [busy, setBusy] = useState(false);

  const seconds = useMemo(() => durationSeconds(from, to), [from, to]);
  const rangeValid = seconds > 0;

  function request(): ExportRequest {
    return {
      namespaces,
      podPattern: podPattern || undefined,
      lineFilter: lineFilter || undefined,
      from: new Date(from).toISOString(),
      to: new Date(to).toISOString(),
    };
  }

  function applyPreset(index: number) {
    const range = rangeFor(PRESETS[index]!);
    setFrom(range.from);
    setTo(range.to);
    setEstimate(null);
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

      <fieldset>
        <legend>Namespaces</legend>
        <div className="checks">
          {me.namespaces.map((namespace) => (
            <label key={namespace.name} className="check">
              <input
                type="checkbox"
                value={namespace.name}
                checked={namespaces.includes(namespace.name)}
                onChange={(e) => {
                  setEstimate(null);
                  setNamespaces((current) =>
                    e.target.checked
                      ? [...current, namespace.name]
                      : current.filter((name) => name !== namespace.name),
                  );
                }}
              />
              <span>
                {namespace.name}
                <small>{namespace.team}</small>
              </span>
            </label>
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend>
          Time range
          {rangeValid && <span className="legend-note">{formatDuration(seconds)}</span>}
        </legend>
        <div className="presets">
          {PRESETS.map((preset, index) => (
            <button
              key={preset.label}
              type="button"
              className="chip"
              onClick={() => applyPreset(index)}
            >
              {preset.label}
            </button>
          ))}
        </div>
        <div className="row">
          <label>
            From
            <input
              type="datetime-local"
              value={from}
              max={toLocalInput(new Date())}
              onChange={(e) => {
                setFrom(e.target.value);
                setEstimate(null);
              }}
            />
          </label>
          <label>
            To
            <input
              type="datetime-local"
              value={to}
              max={toLocalInput(new Date())}
              onChange={(e) => {
                setTo(e.target.value);
                setEstimate(null);
              }}
            />
          </label>
        </div>
        {!rangeValid && <p className="field-error">The range has to end after it starts.</p>}
      </fieldset>

      <fieldset>
        <legend>Narrow it down (optional)</legend>
        <div className="row">
          <label>
            Pods
            <input
              value={podPattern}
              placeholder="api-*"
              onChange={(e) => {
                setPodPattern(e.target.value);
                setEstimate(null);
              }}
            />
            <small>A glob, not a regex. Empty means every pod.</small>
          </label>
          <label>
            Line contains
            <input
              value={lineFilter}
              placeholder="timeout"
              onChange={(e) => {
                setLineFilter(e.target.value);
                setEstimate(null);
              }}
            />
            <small>Plain text, matched literally.</small>
          </label>
        </div>
      </fieldset>

      {estimate && <EstimateCard estimate={estimate} />}

      <div className="actions">
        <button
          type="button"
          onClick={() => run("estimate")}
          disabled={busy || !namespaces.length || !rangeValid}
        >
          Estimate first
        </button>
        <button
          type="button"
          className="primary"
          onClick={() => run("submit")}
          disabled={busy || !namespaces.length || !rangeValid}
        >
          Start export
        </button>
      </div>
    </section>
  );
}

/**
 * The moment the decision is actually made.
 *
 * <p>Sizing happens before any data is read, so this is where someone finds out
 * an export is 40 GB rather than after waiting for it. It is given the weight
 * that deserves.
 */
function EstimateCard({ estimate }: { estimate: Estimate }) {
  const namespaces = Object.entries(estimate.bytesByNamespace).sort((a, b) => b[1] - a[1]);
  const filtered = estimate.filteredBytes;
  const hasFilter = estimate.selector.includes("|=");
  // What you download is what survives the filter; what Loki reads is the
  // whole stream either way, and that is what the quota is judged on.
  const download = filtered ?? estimate.estimatedBytes;

  return (
    <div className="estimate" aria-live="polite">
      <div className="estimate-headline">
        <strong>{formatBytes(download)}</strong>
        <span className="quiet">
          {filtered !== null ? "to download, estimated from a sample" : "to download"}
        </span>
      </div>

      {hasFilter && (
        <p className="estimate-note">
          {formatBytes(estimate.estimatedBytes)} is read from Loki before your line filter is
          applied{filtered === null && ", and there was too little data to judge how much the filter keeps"}.
          Quota is judged on what is read.
        </p>
      )}

      <p className="quiet estimate-files">
        {estimate.windowCount} file{estimate.windowCount === 1 ? "" : "s"}, about{" "}
        {formatBytes(download / Math.max(estimate.windowCount, 1))} each
      </p>
      {namespaces.length > 1 && (
        <ul className="breakdown">
          {namespaces.map(([namespace, bytes]) => (
            <li key={namespace}>
              <span>{namespace}</span>
              <span className="quiet">{formatBytes(bytes)}</span>
            </li>
          ))}
        </ul>
      )}
      <details>
        <summary>What will be queried</summary>
        <code>{estimate.selector}</code>
        <p className="quiet">
          Generated from your selections. Windows of {formatDuration(estimate.windowSeconds)} are
          extracted in parallel.
        </p>
      </details>
    </div>
  );
}
