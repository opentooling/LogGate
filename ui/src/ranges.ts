/** Time range presets and the conversions the form needs. */

export type Preset = { label: string; seconds: number };

/**
 * Deliberately coarse. Exports are for bulk retrieval, and offering minutes
 * would invite people to use this where a Grafana query belongs.
 */
export const PRESETS: Preset[] = [
  { label: "Last hour", seconds: 3600 },
  { label: "Last 6 hours", seconds: 6 * 3600 },
  { label: "Last 24 hours", seconds: 24 * 3600 },
  { label: "Last 2 days", seconds: 48 * 3600 },
];

/** A local datetime value an `<input type="datetime-local">` accepts. */
export function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** The range a preset covers, ending now. */
export function rangeFor(preset: Preset): { from: string; to: string } {
  const now = new Date();
  return {
    from: toLocalInput(new Date(now.getTime() - preset.seconds * 1000)),
    to: toLocalInput(now),
  };
}

/** Seconds between two datetime-local values, or 0 if they do not make sense. */
export function durationSeconds(from: string, to: string): number {
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 0;
  return Math.round((end - start) / 1000);
}
