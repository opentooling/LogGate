import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react";
import { coverage, groupOptions, matches, setAll, type Option } from "./picker";

/**
 * One part of the query: a chip that says what is chosen, and opens a panel
 * to change it.
 *
 * <p>The whole query fits on one line this way, however many clusters or
 * namespaces there are to choose from, and each panel has the room its choice
 * needs. A click outside or Escape closes it; Escape returns focus to the chip.
 */
export function QueryChip({
  label,
  value,
  testId,
  empty,
  panelWidth = "20rem",
  children,
}: {
  label: string;
  value: ReactNode;
  testId: string;
  /** Styled as not yet chosen, e.g. a filter not in use. */
  empty?: boolean;
  panelWidth?: string;
  children: (close: () => void) => ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const panelId = useId();

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
    button.current?.focus();
  }

  return (
    <div
      className="chip-wrap"
      ref={root}
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) {
          event.stopPropagation();
          close();
        }
      }}
    >
      <button
        ref={button}
        type="button"
        className={`qchip${open ? " qchip-open" : ""}${empty ? " qchip-empty" : ""}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={panelId}
        data-testid={`${testId}-chip`}
        onClick={() => setOpen((was) => !was)}
      >
        <span className="qchip-label">{label}</span>
        <span className="qchip-value">{value}</span>
        <span className="qchip-caret" aria-hidden="true">
          ▾
        </span>
      </button>
      {open && (
        <div
          className="qpanel"
          id={panelId}
          role="dialog"
          aria-label={label}
          style={{ width: panelWidth }}
          data-testid={`${testId}-panel`}
        >
          {children(close)}
        </div>
      )}
    </div>
  );
}

/**
 * Choosing any number of things from a list of any length: a search box,
 * select all and clear, and the options in groups, each group with a toggle.
 * The keyboard works as in a menu: arrows move, Enter in the search picks the
 * first match.
 */
export function ChoiceList({
  noun,
  options,
  chosen,
  onChange,
  grouping,
  testId,
  onDone,
}: {
  noun: string;
  options: Option[];
  chosen: string[];
  onChange: (chosen: string[]) => void;
  grouping: "none" | "prefix" | "field";
  testId: string;
  onDone: () => void;
}) {
  const [filter, setFilter] = useState("");
  const list = useRef<HTMLDivElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const shown = useMemo(() => options.filter((o) => matches(o, filter)), [options, filter]);
  const groups = useMemo(() => groupOptions(shown, grouping), [shown, grouping]);
  const shownValues = shown.map((o) => o.value);
  const searchable = options.length > 6;

  function move(from: Element | null, step: 1 | -1) {
    const boxes = Array.from(
      list.current?.querySelectorAll<HTMLInputElement>('input[type="checkbox"]') ?? [],
    );
    const next = (from ? boxes.indexOf(from as HTMLInputElement) : -1) + step;
    if (next < 0) search.current?.focus();
    else if (next < boxes.length) boxes[next]!.focus();
  }

  return (
    <div className="choices">
      <div className="choices-tools">
        {searchable && (
          <input
            ref={search}
            className="choices-search"
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
                e.preventDefault();
                const first = shown[0]!.value;
                onChange(chosen.includes(first) ? chosen.filter((c) => c !== first) : [...chosen, first]);
              }
            }}
          />
        )}
        <button
          type="button"
          className="text-button"
          disabled={coverage(chosen, shownValues) === "all" || shown.length === 0}
          onClick={() => onChange(setAll(chosen, shownValues, true))}
        >
          {filter.trim() ? `Select ${shown.length} shown` : "Select all"}
        </button>
        <button
          type="button"
          className="text-button"
          disabled={chosen.length === 0}
          onClick={() => onChange([])}
        >
          Clear
        </button>
      </div>

      <div
        className="choices-list"
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
          <ChoiceGroup
            key={group.name ?? ""}
            name={group.name}
            options={group.options}
            chosen={chosen}
            onChange={onChange}
          />
        ))}
        {shown.length === 0 && (
          <p className="quiet choices-empty">
            {options.length === 0 ? `No ${noun}s to choose from.` : `No ${noun} matches “${filter}”.`}
          </p>
        )}
      </div>

      <div className="choices-foot">
        <span className="quiet">
          {chosen.length} of {options.length} chosen
        </span>
        <button type="button" className="small" onClick={onDone}>
          Done
        </button>
      </div>
    </div>
  );
}

function ChoiceGroup({
  name,
  options,
  chosen,
  onChange,
}: {
  name: string | null;
  options: Option[];
  chosen: string[];
  onChange: (chosen: string[]) => void;
}) {
  const values = options.map((o) => o.value);
  const state = coverage(chosen, values);
  const toggle = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (toggle.current) toggle.current.indeterminate = state === "some";
  }, [state]);

  return (
    <div className="choice-group" role={name ? "group" : undefined} aria-label={name ?? undefined}>
      {name && (
        <label className="choice-group-head">
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
      {options.map((option) => (
        <label key={option.value} className="choice" title={option.value}>
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
          <span className="choice-name">{option.value}</span>
          {option.detail && <span className="choice-detail">{option.detail}</span>}
        </label>
      ))}
    </div>
  );
}

/** A short summary of a choice for its chip: two names, then a count. */
export function summary(chosen: string[], none: string): string {
  if (chosen.length === 0) return none;
  if (chosen.length <= 2) return chosen.join(", ");
  return `${chosen[0]} +${chosen.length - 1}`;
}
