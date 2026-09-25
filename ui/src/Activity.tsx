import { useEffect, useState } from "react";
import { api, ApiError, type ActivityPeriod, type ActivityPoint, type ActivityReport } from "./api";
import { bucketLabel, failureLabel, niceMax, rankFailures } from "./charts";
import { formatBytes, formatCount, formatDuration } from "./format";

const PERIODS: { value: ActivityPeriod; label: string }[] = [
  { value: "24h", label: "24 hours" },
  { value: "7d", label: "7 days" },
  { value: "30d", label: "30 days" },
];

/** Refreshed this often while the page is open. */
const REFRESH_MS = 30_000;

/**
 * How exporting has gone, for everyone who uses this LogGate.
 *
 * <p>The same questions as the Grafana dashboard, asked of the database
 * rather than of Prometheus: is anything stuck, is anything failing and why,
 * and how much is leaving. Counts only; nobody's exports are named.
 */
export function Activity({ onError }: { onError: (message: string) => void }) {
  const [period, setPeriod] = useState<ActivityPeriod>("24h");
  const [report, setReport] = useState<ActivityReport | null>(null);
  const [updated, setUpdated] = useState<Date | null>(null);

  useEffect(() => {
    let current = true;
    const load = () =>
      api
        .activity(period)
        .then((next) => {
          if (!current) return;
          setReport(next);
          setUpdated(new Date());
        })
        .catch((e) => current && onError(e instanceof ApiError ? e.message : String(e)));
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      current = false;
      clearInterval(timer);
    };
  }, [period, onError]);

  return (
    <section className="card activity" data-testid="activity">
      <div className="activity-head">
        <div>
          <h2>Activity</h2>
          <p className="quiet activity-scope">
            Every export on this LogGate, from everyone. Counts only.
            {updated && <> Updated {updated.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}.</>}
          </p>
        </div>
        <div className="segmented" role="group" aria-label="Period">
          {PERIODS.map((p) => (
            <button
              key={p.value}
              type="button"
              aria-pressed={period === p.value}
              onClick={() => setPeriod(p.value)}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {!report ? (
        <p className="quiet">Loading…</p>
      ) : (
        <>
          <h3 className="activity-section">Right now</h3>
          <div className="tiles">
            <Tile label="Exports in flight" value={formatCount(report.now.activeExports)} />
            <Tile
              label="Windows waiting"
              value={formatCount(report.now.windowsPending)}
              hint="Queued for a worker. Growing while nothing completes means stuck."
              tone={report.now.windowsPending > 0 && report.now.windowsRunning === 0 ? "warn" : undefined}
            />
            <Tile label="Windows running" value={formatCount(report.now.windowsRunning)} />
          </div>

          <h3 className="activity-section">Last {PERIODS.find((p) => p.value === period)!.label}</h3>
          <div className="tiles">
            <Tile label="Accepted" value={formatCount(report.totals.submitted)} />
            <Tile label="Ready" value={formatCount(report.totals.ready)} tone="ok" />
            <Tile
              label="Failed"
              value={formatCount(report.totals.failed)}
              tone={report.totals.failed > 0 ? "bad" : undefined}
              hint={report.totals.cancelled > 0 ? `${formatCount(report.totals.cancelled)} cancelled` : undefined}
            />
            <Tile
              label="Refused"
              value={formatCount(report.totals.refused + report.totals.denied)}
              hint={`${formatCount(report.totals.refused)} over quota, ${formatCount(report.totals.denied)} not allowed`}
            />
            <Tile
              label="Exported"
              value={formatBytes(report.totals.bytesExported)}
              hint={`${formatCount(report.totals.entriesExported)} entries, uncompressed`}
            />
            <Tile
              label="Time to ready"
              value={report.durationSeconds.p50 === null ? "–" : formatDuration(report.durationSeconds.p50)}
              hint={
                report.durationSeconds.p95 === null
                  ? "Median, once an export finishes"
                  : `Median; 95% within ${formatDuration(report.durationSeconds.p95)}`
              }
            />
          </div>

          <div className="charts">
            <Chart
              title="Outcomes"
              report={report}
              series={[
                { key: "ready", label: "ready", tone: "ok" },
                { key: "failed", label: "failed", tone: "bad" },
                { key: "cancelled", label: "cancelled", tone: "quiet" },
              ]}
              format={formatCount}
            />
            <Chart
              title="Submissions"
              report={report}
              series={[
                { key: "submitted", label: "accepted", tone: "accent" },
                { key: "refused", label: "over quota", tone: "warn" },
              ]}
              format={formatCount}
            />
            <Chart
              title="Written"
              report={report}
              series={[{ key: "bytesExported", label: "uncompressed", tone: "accent" }]}
              format={formatBytes}
            />
            <Failures failures={report.failures} />
          </div>
        </>
      )}
    </section>
  );
}

function Tile({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: "ok" | "bad" | "warn";
}) {
  return (
    <div className={`tile${tone ? ` tile-${tone}` : ""}`} data-testid="tile">
      <span className="tile-label">{label}</span>
      <strong className="tile-value">{value}</strong>
      {hint && <small>{hint}</small>}
    </div>
  );
}

type SeriesKey = Exclude<keyof ActivityPoint, "at">;

const WIDTH = 600;
const HEIGHT = 170;
const LEFT = 52;
const BOTTOM = 22;
/** Room above the top gridline for its label. */
const TOP = 8;

/**
 * Stacked bars, one per bucket. Plain SVG: a charting library would outweigh
 * the rest of the page for four small charts.
 */
function Chart({
  title,
  report,
  series,
  format,
}: {
  title: string;
  report: ActivityReport;
  series: { key: SeriesKey; label: string; tone: "ok" | "bad" | "warn" | "accent" | "quiet" }[];
  format: (value: number) => string;
}) {
  const points = report.series;
  const total = (p: ActivityPoint) => series.reduce((sum, s) => sum + p[s.key], 0);
  const top = niceMax(Math.max(0, ...points.map(total)));
  const plotWidth = WIDTH - LEFT;
  const plotHeight = HEIGHT - BOTTOM - TOP;
  const slot = plotWidth / Math.max(points.length, 1);
  const bar = Math.max(1, slot * 0.72);
  const y = (value: number) => TOP + plotHeight - (value / top) * plotHeight;
  const labelled = new Set([0, Math.floor(points.length / 2), points.length - 1]);

  return (
    <figure className="chart" data-testid="chart">
      <figcaption>
        <span>{title}</span>
        <span className="legend">
          {series.map((s) => (
            <span key={s.key} className={`legend-item tone-${s.tone}`}>
              {s.label}
            </span>
          ))}
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={`${title} per bucket`}>
        {[0, 0.5, 1].map((fraction) => (
          <g key={fraction}>
            <line className="gridline" x1={LEFT} x2={WIDTH} y1={y(top * fraction)} y2={y(top * fraction)} />
            <text className="axis" x={LEFT - 6} y={y(top * fraction) + 4} textAnchor="end">
              {format(top * fraction)}
            </text>
          </g>
        ))}
        {points.map((point, index) => {
          let base = 0;
          const x = LEFT + index * slot + (slot - bar) / 2;
          return (
            <g key={point.at}>
              <title>
                {`${bucketLabel(point.at, report.bucketSeconds)}: ` +
                  series.map((s) => `${format(point[s.key])} ${s.label}`).join(", ")}
              </title>
              {series.map((s) => {
                const value = point[s.key];
                const rect = (
                  <rect
                    key={s.key}
                    className={`bar-${s.tone}`}
                    x={x}
                    width={bar}
                    y={y(base + value)}
                    height={Math.max(0, y(base) - y(base + value))}
                  />
                );
                base += value;
                return rect;
              })}
              {/* The whole slot takes the hover, not only the bar, so an
                  empty bucket can still be read. */}
              <rect className="hit" x={LEFT + index * slot} width={slot} y={TOP} height={plotHeight} />
              {labelled.has(index) && (
                <text
                  className="axis"
                  x={x + bar / 2}
                  y={HEIGHT - 6}
                  textAnchor={index === 0 ? "start" : index === points.length - 1 ? "end" : "middle"}
                >
                  {bucketLabel(point.at, report.bucketSeconds)}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </figure>
  );
}

function Failures({ failures }: { failures: Record<string, number> }) {
  const ranked = rankFailures(failures);
  const most = Math.max(1, ...ranked.map(([, count]) => count));
  return (
    <figure className="chart" data-testid="failures">
      <figcaption>
        <span>Why exports failed</span>
      </figcaption>
      {ranked.length === 0 ? (
        <p className="quiet chart-empty">Nothing failed in this period.</p>
      ) : (
        <ul className="reasons">
          {ranked.map(([code, count]) => (
            <li key={code}>
              <div className="reason-label">
                <span>{failureLabel(code)}</span>
                <span className="quiet">{formatCount(count)}</span>
              </div>
              <div className="progress" aria-hidden="true">
                <div className="bar bar-full" style={{ width: `${(count / most) * 100}%` }} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </figure>
  );
}
