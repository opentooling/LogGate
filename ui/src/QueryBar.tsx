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
import { ChoiceList, QueryChip, summary } from "./Chips";
import { formatApprox, formatBytes, formatDuration, formatWhen, usedFraction } from "./format";
import type { Draft } from "./jobs";
import { PRESETS, durationSeconds, rangeFor, toLocalInput } from "./ranges";

/** Waits this long after the last change before asking the server. */
const SETTLE_MS = 350;

/** Waits longer before sizing, which asks Loki for a volume. */
const ESTIMATE_SETTLE_MS = 700;

const FORMATS: { value: OutputFormat; label: string; short: string; detail: string }[] = [
  {
    value: "JSON",
    label: "JSON lines",
    short: "JSON",
    detail: "Each line a JSON object with the timestamp, the labels and the log line. Reads with jq.",
  },
  {
    value: "RAW",
    label: "Raw log lines",
    short: "Raw lines",
    detail: "The lines exactly as logged, for grep and less. No timestamps or labels are added.",
  },
];

/**
 * The whole export on one line: what, when, how, its size, and Start.
 *
 * <p>Each chip opens its own panel, so thirty clusters take no more room than
 * three. The size is asked for as the choices settle rather than on a button,
 * so the number that decides whether to start is always the current one.
 */
export function QueryBar({
  me,
  quota,
  draft,
  onSubmitted,
  onError,
}: {
  me: Me;
  quota: Quota | null;
  /** A query to start from, when an export is run again. */
  draft: (Draft & { nonce: number }) | null;
  onSubmitted: () => void;
  onError: (message: string) => void;
}) {
  // Namespaces wait for a cluster where there are clusters to choose: every
  // namespace of every cluster is the costliest question to put to Loki.
  const clusterFirst = me.mode === "OPEN" && me.clusters.length > 0;

  const [clusters, setClusters] = useState<string[]>([]);
  const [namespaces, setNamespaces] = useState<string[]>(
    me.namespacesOptional || me.namespaces.length === 0 ? [] : [me.namespaces[0]!.name],
  );
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
  const [preset, setPreset] = useState<number | null>(0);
  const [sizing, setSizing] = useState<Sizing | null>(null);
  const [estimating, setEstimating] = useState(false);
  const [estimateProblem, setEstimateProblem] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);

  const seconds = useMemo(() => durationSeconds(from, to), [from, to]);
  const rangeValid = seconds > 0;
  const rangeTooLong = quota !== null && seconds > quota.maxRangeSeconds;
  const scopeChosen = (!clusterFirst || clusters.length > 0) && (namespaces.length > 0 || me.namespacesOptional);
  const ready = scopeChosen && rangeValid && !rangeTooLong;

  // Run again: the same scope and format, over the same length of time, ending now.
  useEffect(() => {
    if (!draft) return;
    setClusters(draft.clusters);
    setNamespaces(draft.namespaces);
    setFormat(draft.format);
    const now = new Date();
    const match = PRESETS.findIndex((p) => p.seconds === draft.seconds);
    setFrom(toLocalInput(new Date(now.getTime() - draft.seconds * 1000)));
    setTo(toLocalInput(now));
    setPreset(match >= 0 ? match : null);
    setPods([]);
    setPodPattern("");
    setLineFilter("");
  }, [draft]);

  // In open mode each cluster has its own namespaces, listed once chosen. A
  // namespace no longer on offer is dropped rather than silently kept.
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

  // Pods that ran in the chosen namespaces over the chosen range, once both are settled.
  const podsAskable = me.podsListable && podMode === "pick" && namespaces.length > 0 && rangeValid && !rangeTooLong;
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
        .pods(clusters, namespaces, new Date(from).toISOString(), new Date(to).toISOString())
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

  const request: ExportRequest = useMemo(
    () => ({
      clusters: me.mode === "OPEN" ? clusters : undefined,
      namespaces,
      pods: podMode === "pick" && pods.length > 0 ? pods : undefined,
      podPattern: me.podPatternAllowed && podMode === "pattern" && podPattern ? podPattern : undefined,
      lineFilter: lineFilter || undefined,
      format,
      from: new Date(from).toISOString(),
      to: new Date(to).toISOString(),
    }),
    [me, clusters, namespaces, podMode, pods, podPattern, lineFilter, format, from, to],
  );

  // The size, asked for whenever the query settles.
  const requestKey = JSON.stringify(request);
  useEffect(() => {
    setSizing(null);
    setEstimateProblem(null);
    if (!ready) return;
    let current = true;
    setEstimating(true);
    const timer = setTimeout(() => {
      api
        .estimate(request)
        .then((result) => current && setSizing(result))
        .catch((e) => current && setEstimateProblem(e instanceof ApiError ? e.message : String(e)))
        .finally(() => current && setEstimating(false));
    }, ESTIMATE_SETTLE_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestKey, ready]);

  async function start() {
    setStarting(true);
    onError("");
    try {
      await api.submit(request);
      onSubmitted();
    } catch (e) {
      onError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setStarting(false);
    }
  }

  const refused = sizing !== null && !sizing.admission.allowed;
  const download = sizing ? (sizing.estimate.filteredBytes ?? sizing.estimate.estimatedBytes) : null;

  return (
    <section className="query" aria-label="New export" data-testid="query">
      <div className="query-chips">
        {me.clusters.length > 0 &&
          (me.mode === "TEAM_LABEL" ? (
            <span className="qchip qchip-fixed" data-testid="pinned-cluster" title="Exports come from this cluster only: it is the one whose namespace owners LogGate can check.">
              <span className="qchip-label">Cluster</span>
              <span className="qchip-value">{me.clusters[0]}</span>
            </span>
          ) : (
            <QueryChip label="Clusters" value={summary(clusters, "Choose…")} empty={clusters.length === 0} testId="clusters">
              {(close) => (
                <ChoiceList
                  noun="cluster"
                  options={me.clusters.map((value) => ({ value }))}
                  chosen={clusters}
                  onChange={setClusters}
                  grouping="prefix"
                  testId="clusters"
                  onDone={close}
                />
              )}
            </QueryChip>
          ))}

        <QueryChip
          label="Namespaces"
          value={summary(namespaces, me.namespacesOptional ? "All" : "Choose…")}
          empty={namespaces.length === 0 && !me.namespacesOptional}
          testId="namespaces"
        >
          {(close) =>
            clusterFirst && clusters.length === 0 ? (
              <p className="qpanel-note">Choose a cluster first. Its namespaces are listed once you have.</p>
            ) : loadingNamespaces && available.length === 0 ? (
              <p className="qpanel-note">Listing namespaces…</p>
            ) : (
              <>
                <ChoiceList
                  noun="namespace"
                  options={available.map((n) => ({ value: n.name, detail: n.team }))}
                  chosen={namespaces}
                  onChange={setNamespaces}
                  grouping="none"
                  testId="namespaces"
                  onDone={close}
                />
                {me.namespacesOptional && (
                  <p className="qpanel-note">None ticked exports every namespace, which can be a great deal.</p>
                )}
              </>
            )
          }
        </QueryChip>

        {(me.podsListable || me.podPatternAllowed) && (
          <QueryChip
            label="Pods"
            value={
              podMode === "pattern" && me.podPatternAllowed
                ? podPattern || "All"
                : pods.length === 0
                  ? "All"
                  : `${pods.length} chosen`
            }
            empty={pods.length === 0 && !podPattern}
            testId="pods"
            panelWidth="24rem"
          >
            {(close) => (
              <PodPanel
                me={me}
                mode={podMode}
                onMode={setPodMode}
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
                onChange={setPods}
                pattern={podPattern}
                onPattern={setPodPattern}
                onDone={close}
              />
            )}
          </QueryChip>
        )}

        <QueryChip
          label="When"
          value={preset !== null ? PRESETS[preset]!.label : `${formatWhen(new Date(from))} · ${rangeValid ? formatDuration(seconds) : "?"}`}
          testId="when"
          panelWidth="22rem"
        >
          {(close) => (
            <div className="when">
              <div className="when-presets" role="group" aria-label="Presets">
                {PRESETS.map((p, index) => (
                  <button
                    key={p.label}
                    type="button"
                    className="chip"
                    aria-pressed={preset === index}
                    onClick={() => {
                      const range = rangeFor(p);
                      setFrom(range.from);
                      setTo(range.to);
                      setPreset(index);
                    }}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
              <label>
                From
                <input
                  type="datetime-local"
                  value={from}
                  max={toLocalInput(new Date())}
                  onChange={(e) => {
                    setFrom(e.target.value);
                    setPreset(null);
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
                    setPreset(null);
                  }}
                />
              </label>
              {!rangeValid && <p className="field-error">The range has to end after it starts.</p>}
              {rangeTooLong && (
                <p className="field-error">
                  One export may cover {formatDuration(quota!.maxRangeSeconds)} at most.
                </p>
              )}
              <div className="choices-foot">
                <span className="quiet">{rangeValid ? formatDuration(seconds) : ""}</span>
                <button type="button" className="small" onClick={close}>
                  Done
                </button>
              </div>
            </div>
          )}
        </QueryChip>

        <QueryChip
          label="Contains"
          value={lineFilter ? `“${lineFilter}”` : "Any line"}
          empty={!lineFilter}
          testId="contains"
        >
          {(close) => (
            <div className="contains">
              <input
                aria-label="Line contains"
                value={lineFilter}
                placeholder="timeout"
                autoFocus
                onChange={(e) => setLineFilter(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && close()}
              />
              <small>Plain text, matched literally.</small>
            </div>
          )}
        </QueryChip>

        <QueryChip label="Output" value={FORMATS.find((f) => f.value === format)!.short} testId="output" panelWidth="22rem">
          {(close) => (
            <div className="formats" role="group" aria-label="Output format">
              {FORMATS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className="format-option"
                  aria-pressed={format === option.value}
                  onClick={() => {
                    setFormat(option.value);
                    close();
                  }}
                >
                  <b>{option.label}</b>
                  <span>{option.detail}</span>
                </button>
              ))}
            </div>
          )}
        </QueryChip>
      </div>

      <div className="query-run">
        <div className="run-size" aria-live="polite" data-testid="estimate">
          {!scopeChosen ? (
            <span className="quiet">
              {clusterFirst && clusters.length === 0 ? "Choose a cluster to begin." : "Choose namespaces to begin."}
            </span>
          ) : !rangeValid || rangeTooLong ? (
            <span className="field-error">Fix the time range.</span>
          ) : estimateProblem ? (
            <span className="field-error">{estimateProblem}</span>
          ) : sizing ? (
            <>
              <strong className="run-bytes">≈ {formatBytes(download!)}</strong>
              <span className="quiet">
                {sizing.estimate.windowCount} file{sizing.estimate.windowCount === 1 ? "" : "s"}
                {sizing.estimate.filteredBytes !== null && " · estimated from a sample"}
              </span>
            </>
          ) : (
            <span className="quiet">{estimating ? "Sizing…" : ""}</span>
          )}
        </div>
        <span className="run-spacer" />
        {quota && <AllowanceChip quota={quota} />}
        <button
          type="button"
          className="primary"
          onClick={start}
          disabled={!ready || starting || refused || sizing === null}
        >
          {starting ? "Starting…" : "Start export"}
        </button>
      </div>

      {refused && (
        <p className="field-error run-refusal" data-testid="refusal">
          This would be refused: {sizing!.admission.reason}
        </p>
      )}
      {sizing && <EstimateDetails sizing={sizing} />}
    </section>
  );
}

function PodPanel({
  me,
  mode,
  onMode,
  listing,
  loading,
  problem,
  waiting,
  chosen,
  onChange,
  pattern,
  onPattern,
  onDone,
}: {
  me: Me;
  mode: "pick" | "pattern";
  onMode: (mode: "pick" | "pattern") => void;
  listing: PodListing | null;
  loading: boolean;
  problem: string | null;
  waiting: string | null;
  chosen: string[];
  onChange: (pods: string[]) => void;
  pattern: string;
  onPattern: (pattern: string) => void;
  onDone: () => void;
}) {
  const showSwitch = me.podsListable && me.podPatternAllowed;
  const picking = me.podsListable && (mode === "pick" || !me.podPatternAllowed);

  return (
    <div className="pods">
      {showSwitch && (
        <div className="segmented" role="group" aria-label="How to choose pods">
          <button type="button" aria-pressed={mode === "pick"} onClick={() => onMode("pick")}>
            Pick from list
          </button>
          <button type="button" aria-pressed={mode === "pattern"} onClick={() => onMode("pattern")}>
            Match a pattern
          </button>
        </div>
      )}
      {picking ? (
        waiting ? (
          <p className="qpanel-note">{waiting}</p>
        ) : loading && !listing ? (
          <p className="qpanel-note">Listing the pods that ran in this range…</p>
        ) : problem ? (
          <p className="qpanel-note">{problem}</p>
        ) : (
          <>
            <ChoiceList
              noun="pod"
              options={(listing?.pods ?? []).map((pod) => ({ value: pod.name, group: pod.namespace }))}
              chosen={chosen}
              onChange={onChange}
              grouping="field"
              testId="pods"
              onDone={onDone}
            />
            <p className="qpanel-note">
              {listing?.truncated
                ? `The first ${listing.pods.length} pods. Choose fewer namespaces or a shorter range to see the rest.`
                : "The pods that ran during this range. None ticked exports every pod."}
            </p>
          </>
        )
      ) : (
        <label className="contains">
          Pod pattern
          <input value={pattern} placeholder="api-*" autoFocus onChange={(e) => onPattern(e.target.value)} />
          <small>A glob, not a regex. Empty means every pod.</small>
        </label>
      )}
    </div>
  );
}

/** What the size is made of, and what will be asked of Loki. */
function EstimateDetails({ sizing }: { sizing: Sizing }) {
  const { estimate, admission } = sizing;
  const byNamespace = Object.entries(estimate.bytesByNamespace).sort((a, b) => b[1] - a[1]);
  const filtered = / \|[=~ ]/.test(estimate.selector);

  return (
    <details className="estimate-details">
      <summary>Details</summary>
      <div className="estimate-grid">
        <div>
          <h3 className="section-label">By namespace</h3>
          <ul className="breakdown">
            {byNamespace.map(([namespace, bytes]) => (
              <li key={namespace}>
                <span>{namespace}</span>
                <span className="num quiet">{formatBytes(bytes)}</span>
              </li>
            ))}
          </ul>
          {filtered && (
            <p className="quiet small-text">
              {formatBytes(estimate.estimatedBytes)} is read from Loki before your filters apply. Quota is
              judged on what is read.
            </p>
          )}
        </div>
        <div>
          <h3 className="section-label">What will be queried</h3>
          <code>{estimate.selector}</code>
          <p className="quiet small-text">
            Windows of {formatDuration(estimate.windowSeconds)}, extracted in parallel.
            {admission.allowed && admission.byteLimit > 0 && (
              <> Capped at {formatBytes(admission.byteLimit)}.</>
            )}
          </p>
        </div>
      </div>
    </details>
  );
}

/** Spending so far, one click from the limits it is judged against. */
function AllowanceChip({ quota }: { quota: Quota }) {
  const budgeted = quota.budgets.filter((budget) => budget.limitBytes > 0);
  const most = budgeted.reduce(
    (worst, budget) => Math.max(worst, usedFraction(budget.usedBytes, budget.limitBytes)),
    0,
  );
  return (
    <div className="allowance-chip" data-testid="allowance">
      <QueryChip
        label="Allowance"
        value={`${Math.round(most * 100)}% · ${quota.yourActiveExports}/${quota.concurrentPerUser} running`}
        testId="allowance"
        panelWidth="20rem"
      >
        {() => (
          <div className="allowance">
            <p className="allowance-title">Your allowance</p>
            {budgeted.map((budget) => {
              const fraction = usedFraction(budget.usedBytes, budget.limitBytes);
              return (
                <div key={budget.holder} className="allowance-team">
                  <div className="allowance-label">
                    <span>{budget.label}</span>
                    <span className="quiet num">
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
                    <div className={`bar${fraction > 0.9 ? " bar-full" : ""}`} style={{ width: `${fraction * 100}%` }} />
                  </div>
                </div>
              );
            })}
            <p className="quiet small-text">
              {budgeted.length > 0 && <>Spending ages out over {formatApprox(quota.budgetWindowSeconds)}. </>}
              One export may cover {formatDuration(quota.maxRangeSeconds)} and{" "}
              {formatBytes(quota.maxEstimatedBytes)}. You have {quota.yourActiveExports} of{" "}
              {quota.concurrentPerUser} exports running, and the platform {quota.activeExports} of{" "}
              {quota.concurrentGlobal}. Finished exports are kept for {formatApprox(quota.retentionSeconds)}.
            </p>
          </div>
        )}
      </QueryChip>
    </div>
  );
}
