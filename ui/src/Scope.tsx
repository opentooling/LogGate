import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import type { Me, Namespace, PodListing } from "./api";
import { coverage, groupOptions, matches, pillWidth, setAll, type Option } from "./picker";

/** Above this many options a list gets a filter, bulk actions and a scroll. */
const LONG_FROM = 8;

/** Chosen values shown as removable chips before the rest are summarised. */
const CHIPS_SHOWN = 10;

/**
 * Choosing any number of things from a list of any length, without the list
 * taking the page.
 *
 * <p>A short list is its options, as pills: one click each, nothing to open. A
 * long one is a field that says what is chosen and opens a searchable panel,
 * so the form stays the same height whether there are three clusters or
 * thirty, and nobody scrolls past options to reach the next choice. The
 * panel filters as you type, selects all shown or clears, groups options with
 * a toggle per group, and takes the keyboard: arrows move, Enter picks the
 * first match, Escape closes.
 */
function Picker({
  legend,
  note,
  noun,
  options,
  chosen,
  onChange,
  grouping,
  testId,
  waiting,
  header,
  footer,
  emptyLabel,
}: {
  legend: string;
  note?: ReactNode;
  noun: string;
  options: Option[];
  chosen: string[];
  onChange: (chosen: string[]) => void;
  grouping: "none" | "prefix" | "field";
  testId: string;
  /** Why there is nothing to choose from yet, shown in place of the list. */
  waiting?: string | null;
  /** Shown under the legend, before the list: controls that change the list. */
  header?: ReactNode;
  footer?: ReactNode;
  /** What the closed field says when nothing is chosen. */
  emptyLabel: string;
}) {
  const long = options.length > LONG_FROM;
  const width = useMemo(() => pillWidth(options), [options]);
  const groups = useMemo(() => groupOptions(options, grouping), [options, grouping]);

  return (
    <fieldset className="picker" data-testid={`${testId}-picker`}>
      <legend>
        {legend}
        {note && <span className="legend-note">{note}</span>}
      </legend>
      {header}

      {waiting ? (
        <p className="picker-waiting" data-testid={`${testId}-waiting`}>
          {waiting}
        </p>
      ) : long ? (
        <Dropdown
          noun={noun}
          options={options}
          chosen={chosen}
          onChange={onChange}
          grouping={grouping}
          testId={testId}
          emptyLabel={emptyLabel}
        />
      ) : (
        <div className="picker-list" data-testid={testId}>
          {groups.map((group) => (
            <PickerGroup
              key={group.name ?? ""}
              name={group.name}
              options={group.options}
              chosen={chosen}
              onChange={onChange}
              width={width}
              variant="pills"
            />
          ))}
          {options.length === 0 && <p className="quiet">No {noun}s to choose from.</p>}
        </div>
      )}
      {footer}
    </fieldset>
  );
}

/** The closed field and its panel, for a list too long to show whole. */
function Dropdown({
  noun,
  options,
  chosen,
  onChange,
  grouping,
  testId,
  emptyLabel,
}: {
  noun: string;
  options: Option[];
  chosen: string[];
  onChange: (chosen: string[]) => void;
  grouping: "none" | "prefix" | "field";
  testId: string;
  emptyLabel: string;
}) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const shown = useMemo(() => options.filter((o) => matches(o, filter)), [options, filter]);
  const groups = useMemo(() => groupOptions(shown, grouping), [shown, grouping]);
  const shownValues = shown.map((o) => o.value);

  // Closes on a click anywhere else, as a menu does.
  useEffect(() => {
    if (!open) return;
    const away = (event: MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  function close() {
    setOpen(false);
    trigger.current?.focus();
  }

  /** Moves focus among the panel's checkboxes, or back up to the search box. */
  function move(from: Element | null, step: 1 | -1) {
    const boxes = Array.from(
      list.current?.querySelectorAll<HTMLInputElement>('input[type="checkbox"]') ?? [],
    );
    const at = from ? boxes.indexOf(from as HTMLInputElement) : -1;
    const next = at + step;
    if (next < 0) {
      root.current?.querySelector<HTMLInputElement>(".dropdown-filter")?.focus();
    } else if (next < boxes.length) {
      boxes[next]!.focus();
    }
  }

  const summary =
    chosen.length === 0
      ? emptyLabel
      : chosen.length <= 3
        ? chosen.join(", ")
        : `${chosen.slice(0, 2).join(", ")} and ${chosen.length - 2} more`;

  return (
    <div
      className="dropdown"
      ref={root}
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) {
          event.stopPropagation();
          close();
        }
      }}
    >
      <button
        ref={trigger}
        type="button"
        className={`dropdown-trigger${chosen.length === 0 ? " dropdown-empty" : ""}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={panelId}
        data-testid={`${testId}-trigger`}
        onClick={() => setOpen((was) => !was)}
      >
        <span className="dropdown-value">{summary}</span>
        {chosen.length > 0 && (
          <span className="dropdown-count">
            {chosen.length} of {options.length}
          </span>
        )}
        <span className="dropdown-caret" aria-hidden="true">
          ▾
        </span>
      </button>

      {open && (
        <div className="dropdown-panel" id={panelId} role="dialog" aria-label={`Choose ${noun}s`}>
          <div className="picker-tools">
            <input
              className="picker-filter dropdown-filter"
              aria-label={`Filter ${noun}s`}
              placeholder={`Search ${options.length} ${noun}s`}
              value={filter}
              autoFocus
              onChange={(e) => setFilter(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "ArrowDown") {
                  e.preventDefault();
                  move(null, 1);
                } else if (e.key === "Enter" && shown.length > 0) {
                  // The first match, as a search box usually means.
                  e.preventDefault();
                  const first = shown[0]!.value;
                  onChange(
                    chosen.includes(first) ? chosen.filter((c) => c !== first) : [...chosen, first],
                  );
                }
              }}
            />
            <button
              type="button"
              className="ghost"
              disabled={coverage(chosen, shownValues) === "all" || shown.length === 0}
              onClick={() => onChange(setAll(chosen, shownValues, true))}
            >
              {filter.trim() ? `Select ${shown.length} shown` : "Select all"}
            </button>
            <button
              type="button"
              className="ghost"
              disabled={chosen.length === 0}
              onClick={() => onChange([])}
            >
              Clear
            </button>
          </div>
          <div
            className="dropdown-list"
            ref={list}
            data-testid={testId}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                move(document.activeElement, e.key === "ArrowDown" ? 1 : -1);
              }
            }}
          >
            {groups.map((group) => (
              <PickerGroup
                key={group.name ?? ""}
                name={group.name}
                options={group.options}
                chosen={chosen}
                onChange={onChange}
                width={0}
                variant="rows"
              />
            ))}
            {shown.length === 0 && <p className="quiet">No {noun} matches “{filter}”.</p>}
          </div>
          <div className="dropdown-foot">
            <span className="quiet">
              {chosen.length} of {options.length} chosen
            </span>
            <button type="button" onClick={close}>
              Done
            </button>
          </div>
        </div>
      )}

      {chosen.length > 0 && (
        <ul className="picked" aria-label={`Chosen ${noun}s`}>
          {chosen.slice(0, CHIPS_SHOWN).map((value) => (
            <li key={value}>
              <span>{value}</span>
              <button
                type="button"
                aria-label={`Remove ${value}`}
                onClick={() => onChange(chosen.filter((c) => c !== value))}
              >
                ×
              </button>
            </li>
          ))}
          {chosen.length > CHIPS_SHOWN && (
            <li className="picked-more">+{chosen.length - CHIPS_SHOWN} more</li>
          )}
        </ul>
      )}
    </div>
  );
}

function PickerGroup({
  name,
  options,
  chosen,
  onChange,
  width,
  variant,
}: {
  name: string | null;
  options: Option[];
  chosen: string[];
  onChange: (chosen: string[]) => void;
  /** Pill width in characters. */
  width: number;
  /** Pills in a grid, or one row each in a dropdown's panel. */
  variant: "pills" | "rows";
}) {
  const values = options.map((o) => o.value);
  const state = coverage(chosen, values);
  const toggle = useRef<HTMLInputElement>(null);
  useEffect(() => {
    // "Some of this group" has no HTML attribute; it is a property only.
    if (toggle.current) toggle.current.indeterminate = state === "some";
  }, [state]);

  return (
    <div className="picker-group" role={name ? "group" : undefined} aria-label={name ?? undefined}>
      {name && (
        <label className="picker-group-head">
          <input
            ref={toggle}
            type="checkbox"
            aria-label={`All of ${name}`}
            checked={state === "all"}
            onChange={(e) => onChange(setAll(chosen, values, e.target.checked))}
          />
          <span>{name}</span>
          <span className="quiet">
            {values.filter((v) => chosen.includes(v)).length}/{values.length}
          </span>
        </label>
      )}
      <div
        className={variant}
        style={
          variant === "pills"
            ? { gridTemplateColumns: `repeat(auto-fill, minmax(min(100%, ${width}ch), 1fr))` }
            : undefined
        }
      >
        {options.map((option) => (
          <label
            key={option.value}
            className={variant === "pills" ? "pill" : "row-option"}
            title={option.value}
          >
            <input
              type="checkbox"
              value={option.value}
              checked={chosen.includes(option.value)}
              onChange={(e) =>
                onChange(
                  e.target.checked
                    ? [...chosen, option.value]
                    : chosen.filter((value) => value !== option.value),
                )
              }
            />
            <span>
              {option.value}
              {option.detail && <small>{option.detail}</small>}
            </span>
          </label>
        ))}
      </div>
    </div>
  );
}

/**
 * Which clusters to export from.
 *
 * <p>In team-label mode there is exactly one, this cluster, because it is the
 * only one whose namespace ownership LogGate can check, so it is shown rather
 * than offered. In open mode every cluster Loki holds logs for is offered, and
 * at least one must be chosen: namespaces are listed per cluster, and
 * "everything, everywhere" is a choice to make on purpose with "Select all"
 * rather than by leaving the list alone.
 */
export function ClusterPicker({
  me,
  chosen,
  onChange,
}: {
  me: Me;
  chosen: string[];
  onChange: (clusters: string[]) => void;
}) {
  const options = useMemo(() => me.clusters.map((value) => ({ value })), [me.clusters]);
  if (me.clusters.length === 0) return null;

  if (me.mode === "TEAM_LABEL") {
    return (
      <fieldset>
        <legend>Cluster</legend>
        <p className="pinned" data-testid="pinned-cluster">
          {me.clusters[0]}
          <small>
            Exports come from this cluster only: it is the one whose namespace owners LogGate
            can check.
          </small>
        </p>
      </fieldset>
    );
  }

  return (
    <Picker
      legend="Clusters"
      note={`${chosen.length} of ${me.clusters.length}`}
      noun="cluster"
      options={options}
      chosen={chosen}
      onChange={onChange}
      grouping="prefix"
      testId="clusters"
      emptyLabel="Choose clusters…"
      footer={
        chosen.length === 0 && <small>Choose a cluster to see its namespaces.</small>
      }
    />
  );
}

/**
 * Which namespaces to export from.
 *
 * <p>Where naming none is allowed, the form says what that means right beside
 * the choice rather than leaving it to be discovered in the estimate.
 */
export function NamespacePicker({
  available,
  chosen,
  optional,
  waiting,
  onChange,
}: {
  available: Namespace[];
  chosen: string[];
  optional: boolean;
  waiting: string | null;
  onChange: (namespaces: string[]) => void;
}) {
  const options = useMemo(
    () => available.map((n) => ({ value: n.name, detail: n.team })),
    [available],
  );
  return (
    <Picker
      legend="Namespaces"
      note={
        waiting
          ? undefined
          : optional
            ? chosen.length === 0
              ? "all"
              : `${chosen.length} chosen`
            : `${chosen.length} of ${available.length}`
      }
      noun="namespace"
      options={options}
      chosen={chosen}
      onChange={onChange}
      grouping="none"
      testId="namespaces"
      emptyLabel={optional ? "Every namespace" : "Choose namespaces…"}
      waiting={waiting}
      footer={
        optional &&
        !waiting && (
          <small>
            {chosen.length === 0
              ? "None ticked exports every namespace, which can be a great deal."
              : "Untick them all to export every namespace."}
          </small>
        )
      }
    />
  );
}

/**
 * Which pods to export from: picked from the pods that actually ran in the
 * chosen namespaces over the chosen range, or matched by a pattern.
 *
 * <p>Picking is the default where a list is available, because the names of
 * pods are exactly what nobody remembers. A pattern is kept for what a list
 * cannot say, such as "every api pod, including ones not started yet", unless
 * the operator has switched patterns off (pods.allowPattern), in which case
 * the list is the only way to narrow by pod.
 */
export function PodPicker({
  listable,
  patternAllowed,
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
}: {
  listable: boolean;
  patternAllowed: boolean;
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
}) {
  const options = useMemo(
    () => (listing?.pods ?? []).map((pod) => ({ value: pod.name, group: pod.namespace })),
    [listing],
  );
  const patternField = (
    <label>
      Pod pattern
      <input value={pattern} placeholder="api-*" onChange={(e) => onPattern(e.target.value)} />
      <small>A glob, not a regex. Empty means every pod.</small>
    </label>
  );

  if (!listable && !patternAllowed) {
    // Nothing to narrow by: every export takes every pod in its namespaces.
    return null;
  }

  if (!listable) {
    return (
      <fieldset>
        <legend>Pods (optional)</legend>
        {patternField}
      </fieldset>
    );
  }

  const switcher = patternAllowed && (
    <div className="segmented" role="group" aria-label="How to choose pods">
      <button type="button" aria-pressed={mode === "pick"} onClick={() => onMode("pick")}>
        Pick from list
      </button>
      <button type="button" aria-pressed={mode === "pattern"} onClick={() => onMode("pattern")}>
        Match a pattern
      </button>
    </div>
  );

  if (mode === "pattern" && patternAllowed) {
    return (
      <fieldset>
        <legend>Pods (optional)</legend>
        {switcher}
        {patternField}
      </fieldset>
    );
  }

  return (
    <Picker
      legend="Pods (optional)"
      note={chosen.length === 0 ? "all" : `${chosen.length} chosen`}
      noun="pod"
      options={options}
      chosen={chosen}
      onChange={onChange}
      grouping="field"
      testId="pods"
      emptyLabel="Every pod"
      waiting={waiting ?? (loading && !listing ? "Listing the pods that ran in this range…" : problem)}
      header={switcher}
      footer={
        <>
          <small>
            {listing?.truncated
              ? `Showing the first ${listing.pods.length} pods. Choose fewer namespaces or a shorter range to see the rest${patternAllowed ? ", or match a pattern" : ""}.`
              : "The pods that ran in these namespaces during this range. None ticked exports every pod."}
          </small>
        </>
      }
    />
  );
}
