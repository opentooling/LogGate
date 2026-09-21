import { useMemo, useState } from "react";
import type { Me, Namespace } from "./api";

/** Above this many namespaces the list gets a filter box and a scroll. */
const FILTER_FROM = 8;

/**
 * Which clusters to export from.
 *
 * <p>In team-label mode there is exactly one, this cluster, because it is the
 * only one whose namespace ownership LogGate can check, so it is shown rather
 * than offered. In open mode every cluster Loki holds logs for is offered, and
 * ticking none means all of them.
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
    <fieldset>
      <legend>
        Clusters
        <span className="legend-note">
          {chosen.length === 0 ? "all" : `${chosen.length} of ${me.clusters.length}`}
        </span>
      </legend>
      <div className="checks" data-testid="clusters">
        {me.clusters.map((cluster) => (
          <label key={cluster} className="check">
            <input
              type="checkbox"
              checked={chosen.includes(cluster)}
              onChange={(e) =>
                onChange(
                  e.target.checked ? [...chosen, cluster] : chosen.filter((c) => c !== cluster),
                )
              }
            />
            <span>{cluster}</span>
          </label>
        ))}
      </div>
      <small>None ticked means every cluster.</small>
    </fieldset>
  );
}

/**
 * Which namespaces to export from.
 *
 * <p>Open mode offers everything Loki holds, which can be hundreds, so a long
 * list gets a filter. Where naming none is allowed, the form says what that
 * means right beside the choice rather than leaving it to be discovered in
 * the estimate.
 */
export function NamespacePicker({
  available,
  chosen,
  optional,
  onChange,
}: {
  available: Namespace[];
  chosen: string[];
  optional: boolean;
  onChange: (namespaces: string[]) => void;
}) {
  const [filter, setFilter] = useState("");
  const shown = useMemo(
    () => available.filter((n) => n.name.includes(filter.trim().toLowerCase())),
    [available, filter],
  );
  const long = available.length > FILTER_FROM;

  return (
    <fieldset>
      <legend>
        Namespaces
        {optional && (
          <span className="legend-note">
            {chosen.length === 0 ? "all" : `${chosen.length} chosen`}
          </span>
        )}
      </legend>
      {long && (
        <input
          className="namespace-filter"
          aria-label="Filter namespaces"
          placeholder={`Filter ${available.length} namespaces`}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
      )}
      <div className={long ? "checks scroll" : "checks"} data-testid="namespaces">
        {shown.map((namespace) => (
          <label key={namespace.name} className="check">
            <input
              type="checkbox"
              value={namespace.name}
              checked={chosen.includes(namespace.name)}
              onChange={(e) =>
                onChange(
                  e.target.checked
                    ? [...chosen, namespace.name]
                    : chosen.filter((name) => name !== namespace.name),
                )
              }
            />
            <span>
              {namespace.name}
              {namespace.team && <small>{namespace.team}</small>}
            </span>
          </label>
        ))}
        {shown.length === 0 && <p className="quiet">No namespace matches “{filter}”.</p>}
      </div>
      {optional && (
        <small>
          {chosen.length === 0
            ? "None ticked exports every namespace, which can be a great deal."
            : "Untick them all to export every namespace."}
        </small>
      )}
    </fieldset>
  );
}
