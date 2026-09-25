import { useEffect, useMemo, useState } from "react";
import {
  api,
  ApiError,
  type ExportRequest,
  type Me,
  type Namespace,
  type OutputFormat,
  type PodListing,
  type Quota,
  type Sizing,
} from "./api";
import { ClusterPicker, NamespacePicker, PodPicker } from "./Scope";
import { formatApprox, formatBytes, formatDuration, usedFraction } from "./format";
import { PRESETS, durationSeconds, rangeFor, toLocalInput } from "./ranges";

/** What an export's files can hold. */
const FORMATS: { value: OutputFormat; label: string; detail: string }[] = [
  {
    value: "JSON",
    label: "JSON lines",
    detail:
      "Each line a JSON object with the timestamp, the labels (pod, container and so on) and the log line. Reads with jq.",
  },
  {
    value: "RAW",
    label: "Raw log lines",
    detail:
      "The log lines exactly as logged, one per line, for grep and less. No timestamps or labels are added, so lines from different pods are not marked as such.",
  },
];

/** Waits this long after the last click before asking the server. */
const SETTLE_MS = 350;

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
  // Namespaces wait for a cluster where there are clusters to choose: listing
  // every namespace of every cluster is the costliest question the page could
  // put to Loki, for a list nobody reads before picking a cluster.
  const clusterFirst = me.mode === "OPEN" && me.clusters.length > 0;

  // Team-label mode starts on the caller's first namespace, as a team usually
  // wants its own. Open mode starts on nothing, which the form explains means
  // everything, rather than guessing at one namespace among hundreds.
  const [namespaces, setNamespaces] = useState<string[]>(
    me.namespacesOptional || me.namespaces.length === 0 ? [] : [me.namespaces[0]!.name],
  );
  const [clusters, setClusters] = useState<string[]>([]);
  const [available, setAvailable] = useState<Namespace[]>(me.namespaces);
  const [loadingNamespaces, setLoadingNamespaces] = useState(false);
  const [podMode, setPodMode] = useState<"pick" | "pattern">(me.podsListable ? "pick" : "pattern");
  const [pods, setPods] = useState<string[]>([]);
  const [podListing, setPodListing] = useState<PodListing | null>(null);
  const [loadingPods, setLoadingPods] = useState(false);
  const [podProblem, setPodProblem] = useState<string | null>(null);
  const [podPattern, setPodPattern] = useState("");
  const [lineFilter, setLineFilter] = useState("");
  const [format, setFormat] = useState<OutputFormat>("JSON");
  const initial = rangeFor(PRESETS[0]!);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  // The preset the range came from, shown as chosen; none once From or To is
  // edited by hand, because the range is then no longer that preset.
  const [activePreset, setActivePreset] = useState<number | null>(0);
  const [sizing, setSizing] = useState<Sizing | null>(null);
  const [busy, setBusy] = useState<"estimate" | "submit" | null>(null);

  const seconds = useMemo(() => durationSeconds(from, to), [from, to]);
  const rangeValid = seconds > 0;
  // The longest range is a fixed limit, not a moving one, so it is worth
  // saying before the request is made rather than after it is refused.
  const rangeTooLong = quota !== null && seconds > quota.maxRangeSeconds;
  const ready =
    busy === null &&
    (!clusterFirst || clusters.length > 0) &&
    (namespaces.length > 0 || me.namespacesOptional) &&
    rangeValid &&
    !rangeTooLong;

  // In open mode the namespaces on offer depend on the clusters chosen: each
  // cluster has its own. A namespace no longer on offer is dropped from the
  // choice rather than silently exported from somewhere it is not.
  useEffect(() => {
    if (!clusterFirst) return;
    if (clusters.length === 0) {
      setAvailable([]);
      setNamespaces([]);
      return;
    }
    let current = true;
    setLoadingNamespaces(true);
    const timer = setTimeout(() => {
      api
        .namespaces(clusters)
        .then((list) => {
          if (!current) return;
          setAvailable(list);
          setNamespaces((chosen) => chosen.filter((name) => list.some((n) => n.name === name)));
        })
        .catch((e) => current && onError(e instanceof ApiError ? e.message : String(e)))
        .finally(() => current && setLoadingNamespaces(false));
    }, SETTLE_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [clusters, clusterFirst, onError]);

  // Pods are listed for the chosen namespaces over the chosen range, and only
  // once both are settled: the list answers "what ran here, then", which is
  // only worth asking when here and then are known.
  const podsAskable =
    me.podsListable && podMode === "pick" && namespaces.length > 0 && rangeValid && !rangeTooLong;
  useEffect(() => {
    if (!podsAskable) {
      setPodListing(null);
      setPodProblem(null);
      return;
    }
    let current = true;
    setLoadingPods(true);
    const timer = setTimeout(() => {
      api
        .pods(
          clusters,
          namespaces,
          new Date(from).toISOString(),
          new Date(to).toISOString(),
        )
        .then((listing) => {
          if (!current) return;
          setPodListing(listing);
          setPodProblem(null);
          setPods((chosen) => chosen.filter((name) => listing.pods.some((p) => p.name === name)));
        })
        .catch((e) => {
          if (!current) return;
          setPodListing(null);
          setPodProblem(e instanceof ApiError ? e.message : String(e));
        })
        .finally(() => current && setLoadingPods(false));
    }, SETTLE_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [podsAskable, clusters, namespaces, from, to]);

  function request(): ExportRequest {
    return {
      clusters: me.mode === "OPEN" ? clusters : undefined,
      namespaces,
      pods: podMode === "pick" && pods.length > 0 ? pods : undefined,
      podPattern:
        me.podPatternAllowed && podMode === "pattern" && podPattern ? podPattern : undefined,
      lineFilter: lineFilter || undefined,
      format,
      from: new Date(from).toISOString(),
      to: new Date(to).toISOString(),
    };
  }

  function applyPreset(index: number) {
    const range = rangeFor(PRESETS[index]!);
    setFrom(range.from);
    setTo(range.to);
    setActivePreset(index);
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

  const namespacesWaiting = clusterFirst
    ? clusters.length === 0
      ? "Choose a cluster first. Its namespaces are listed from Loki once you have."
      : loadingNamespaces && available.length === 0
        ? "Listing namespaces…"
        : null
    : null;

  return (
    <section className="card new-export">
      <h2>New export</h2>

      {quota && <Allowance quota={quota} />}

      <ClusterPicker
        me={me}
        chosen={clusters}
        onChange={(chosen) => {
          setClusters(chosen);
          setSizing(null);
        }}
      />

      <NamespacePicker
        available={available}
        chosen={namespaces}
        optional={me.namespacesOptional}
        waiting={namespacesWaiting}
        onChange={(chosen) => {
          setNamespaces(chosen);
          setSizing(null);
        }}
      />

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
              aria-pressed={activePreset === index}
              onClick={() => applyPreset(index)}
            >
              {preset.label}
            </button>
          ))}
        </div>
        <div className="row row-stacked">
          <label>
            From
            <input
              type="datetime-local"
              value={from}
              max={toLocalInput(new Date())}
              onChange={(e) => {
                setFrom(e.target.value);
                setActivePreset(null);
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
                setActivePreset(null);
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

      <PodPicker
        listable={me.podsListable}
        patternAllowed={me.podPatternAllowed}
        mode={podMode}
        onMode={(mode) => {
          setPodMode(mode);
          setSizing(null);
        }}
        listing={podListing}
        loading={loadingPods}
        problem={podProblem}
        waiting={
          namespaces.length === 0
            ? "Choose namespaces to list the pods that ran in them."
            : !rangeValid || rangeTooLong
              ? "Fix the time range to list pods."
              : null
        }
        chosen={pods}
        onChange={(chosen) => {
          setPods(chosen);
          setSizing(null);
        }}
        pattern={podPattern}
        onPattern={(pattern) => {
          setPodPattern(pattern);
          setSizing(null);
        }}
      />

      <fieldset>
        <legend>Line contains (optional)</legend>
        <label>
          <input
            aria-label="Line contains"
            value={lineFilter}
            placeholder="timeout"
            onChange={(e) => {
              setLineFilter(e.target.value);
              setSizing(null);
            }}
          />
          <small>Plain text, matched literally.</small>
        </label>
      </fieldset>

      <fieldset>
        <legend>Output</legend>
        <div className="segmented" role="group" aria-label="Output format">
          {FORMATS.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={format === option.value}
              onClick={() => {
                setFormat(option.value);
                setSizing(null);
              }}
            >
              {option.label}
            </button>
          ))}
        </div>
        <small>{FORMATS.find((option) => option.value === format)!.detail}</small>
      </fieldset>

      {sizing && <EstimateCard sizing={sizing} />}

      <div className="actions actions-sticky">
        <p className="scope-summary" data-testid="scope-summary">
          {summarise(
            me,
            clusters,
            namespaces,
            podMode === "pick" || !me.podPatternAllowed ? pods.length : podPattern ? -1 : 0,
            seconds,
          )}
          {format === "RAW" && " · raw lines"}
        </p>
        <div className="actions-buttons">
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
      </div>

    </section>
  );
}

/**
 * The whole choice in one line, kept beside the buttons, so what is about to
 * be exported is readable without scrolling back up a long form.
 *
 * @param pods how many pods are picked; -1 for a pattern
 */
export function summarise(
  me: Me,
  clusters: string[],
  namespaces: string[],
  pods: number,
  seconds: number,
): string {
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
  const parts: string[] = [];
  if (me.mode === "OPEN" && me.clusters.length > 0) {
    parts.push(clusters.length === 0 ? "no cluster yet" : plural(clusters.length, "cluster"));
  }
  parts.push(namespaces.length === 0 ? (me.namespacesOptional ? "every namespace" : "no namespace yet") : plural(namespaces.length, "namespace"));
  parts.push(pods === -1 ? "pods by pattern" : pods === 0 ? "every pod" : plural(pods, "pod"));
  if (seconds > 0) parts.push(formatDuration(seconds));
  return parts.join(" · ");
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
  // Anything after the stream selector, a line or a pod filter, is invisible
  // to Loki's index, so the download is estimated from a sample.
  const hasFilter = / \|[=~ ]/.test(estimate.selector);
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
          {formatBytes(estimate.estimatedBytes)} is read from Loki before your filters are
          applied{filtered === null && ", and there was too little data to judge how much the filters keep"}.
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
  const budgeted = quota.budgets.filter((budget) => budget.limitBytes > 0);
  return (
    <section className="allowance" data-testid="allowance" aria-label="Your allowance">
      <div className="allowance-head">
        <span className="allowance-title">Your allowance</span>
        <span className="quiet">
          {quota.yourActiveExports} of {quota.concurrentPerUser} exports running
        </span>
      </div>
      {budgeted.map((budget) => {
        const fraction = usedFraction(budget.usedBytes, budget.limitBytes);
        return (
          <div key={budget.holder} className="allowance-team">
            <div className="allowance-label">
              <span>{budget.label}</span>
              <span className="quiet">
                {formatBytes(budget.usedBytes)} of {formatBytes(budget.limitBytes)}
              </span>
            </div>
            <div
              className="progress"
              role="progressbar"
              aria-label={`${budget.label} budget used`}
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
      <details>
        <summary>Limits</summary>
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
    </section>
  );
}
