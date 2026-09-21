import { useMemo, useState } from "react";
import { api, ApiError, type Quota, type Sizing, type ExportRequest, type Me } from "./api";
import { formatApprox, formatBytes, formatDuration, usedFraction } from "./format";
import { PRESETS, durationSeconds, rangeFor, toLocalInput } from "./ranges";

export function NewExport({
  me,
  quota,
  onSubmitted,
  onError,
}: {
  me: Me;
  quota: Quota | null;
  onSubmitted: () => void;
  onError: (message: string) => void;
}) {
  const [namespaces, setNamespaces] = useState<string[]>([me.namespaces[0]!.name]);
  const [podPattern, setPodPattern] = useState("");
  const [lineFilter, setLineFilter] = useState("");
  const initial = rangeFor(PRESETS[0]!);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [sizing, setSizing] = useState<Sizing | null>(null);
  const [busy, setBusy] = useState<"estimate" | "submit" | null>(null);

  const seconds = useMemo(() => durationSeconds(from, to), [from, to]);
  const rangeValid = seconds > 0;
  // The longest range is a fixed limit, not a moving one, so it is worth
  // saying before the request is made rather than after it is refused.
  const rangeTooLong = quota !== null && seconds > quota.maxRangeSeconds;
  const ready = busy === null && namespaces.length > 0 && rangeValid && !rangeTooLong;

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
    setSizing(null);
  }

  async function run(action: "estimate" | "submit") {
    setBusy(action);
    onError("");
    try {
      if (action === "estimate") {
        setSizing(await api.estimate(request()));
      } else {
        await api.submit(request());
        setSizing(null);
        onSubmitted();
      }
    } catch (e) {
      onError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(null);
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
                  setSizing(null);
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
                setSizing(null);
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
                setSizing(null);
              }}
            />
          </label>
        </div>
        {!rangeValid && <p className="field-error">The range has to end after it starts.</p>}
        {rangeTooLong && (
          <p className="field-error">
            One export may cover {formatDuration(quota!.maxRangeSeconds)} at most. Shorten this
            range, or run it in two.
          </p>
        )}
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
                setSizing(null);
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
                setSizing(null);
              }}
            />
            <small>Plain text, matched literally.</small>
          </label>
        </div>
      </fieldset>

      {sizing && <EstimateCard sizing={sizing} />}

      <div className="actions">
        <button type="button" onClick={() => run("estimate")} disabled={!ready}>
          {busy === "estimate" ? "Estimating…" : "Estimate first"}
        </button>
        <button type="button" className="primary" onClick={() => run("submit")} disabled={!ready}>
          {busy === "submit"
            ? "Starting…"
            : sizing
              ? `Start export (${formatBytes(downloadBytes(sizing))})`
              : "Start export"}
        </button>
      </div>

      {quota && <Allowance quota={quota} />}
    </section>
  );
}

/** What the export will actually hand back, as opposed to what Loki reads. */
function downloadBytes(sizing: Sizing): number {
  return sizing.estimate.filteredBytes ?? sizing.estimate.estimatedBytes;
}

/**
 * The moment the decision is actually made.
 *
 * <p>Sizing happens before any data is read, so this is where someone finds out
 * an export is 40 GB rather than after waiting for it. It is given the weight
 * that deserves.
 */
function EstimateCard({ sizing }: { sizing: Sizing }) {
  const { estimate, admission } = sizing;
  const namespaces = Object.entries(estimate.bytesByNamespace).sort((a, b) => b[1] - a[1]);
  const filtered = estimate.filteredBytes;
  const hasFilter = estimate.selector.includes("|=");
  // What you download is what survives the filter; what Loki reads is the
  // whole stream either way, and that is what the quota is judged on.
  const download = downloadBytes(sizing);

  return (
    <div className={`estimate${admission.allowed ? "" : " estimate-refused"}`} aria-live="polite">
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

      {!admission.allowed && (
        <p className="field-error" data-testid="refusal">
          This would be refused: {admission.reason}
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
          {admission.allowed && admission.byteLimit > 0 && (
            <> The job is capped at {formatBytes(admission.byteLimit)} and stops there.</>
          )}
        </p>
      </details>
    </div>
  );
}

/**
 * What is left of the limits, while there is still time to spend it well.
 *
 * <p>A quota only discovered by being refused teaches nothing. Shown here, it
 * is something to plan a range around.
 */
function Allowance({ quota }: { quota: Quota }) {
  const budgeted = quota.teams.filter((team) => team.limitBytes > 0);
  return (
    <details className="allowance" data-testid="allowance">
      <summary>Your allowance</summary>
      {budgeted.map((team) => {
        const fraction = usedFraction(team.usedBytes, team.limitBytes);
        return (
          <div key={team.team} className="allowance-team">
            <div className="allowance-label">
              <span>{team.team}</span>
              <span className="quiet">
                {formatBytes(team.usedBytes)} of {formatBytes(team.limitBytes)}
              </span>
            </div>
            <div
              className="progress"
              role="progressbar"
              aria-label={`${team.team} budget used`}
              aria-valuenow={Math.round(fraction * 100)}
              aria-valuemin={0}
              aria-valuemax={100}
            >
              <div
                className={`bar${fraction > 0.9 ? " bar-full" : ""}`}
                style={{ width: `${fraction * 100}%` }}
              />
            </div>
          </div>
        );
      })}
      <p className="quiet">
        {budgeted.length > 0 && (
          <>
            Spending ages out over {formatApprox(quota.budgetWindowSeconds)}.{" "}
          </>
        )}
        One export may cover {formatDuration(quota.maxRangeSeconds)} and{" "}
        {formatBytes(quota.maxEstimatedBytes)}. You have {quota.yourActiveExports} of{" "}
        {quota.concurrentPerUser} exports running, and the platform {quota.activeExports} of{" "}
        {quota.concurrentGlobal}. Finished exports are kept for{" "}
        {formatApprox(quota.retentionSeconds)}.
      </p>
    </details>
  );
}
