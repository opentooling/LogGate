/** Formatting helpers, kept apart so they can be tested on their own. */

/** Bytes as something a person can read at a glance. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** A duration in whole units, for window sizes and ranges. */
export function formatDuration(seconds: number): string {
  if (seconds <= 0) return "0s";
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const parts: string[] = [];
  if (days) parts.push(`${days}d`);
  if (hours) parts.push(`${hours}h`);
  if (minutes && !days) parts.push(`${minutes}m`);
  // Measured durations arrive with fractions; nobody needs 1.6063479999s.
  if (!parts.length) parts.push(`${Math.max(1, Math.round(seconds))}s`);
  return parts.join(" ");
}

/** Large counts, abbreviated. */
export function formatCount(count: number): string {
  if (count < 1000) return String(count);
  if (count < 1_000_000) return `${(count / 1000).toFixed(count < 10_000 ? 1 : 0)}k`;
  return `${(count / 1_000_000).toFixed(count < 10_000_000 ? 1 : 0)}M`;
}

/**
 * Which way to collect an export of this size.
 *
 * <p>A browser download of tens of gigabytes is a bad idea, and people will do
 * it unless told otherwise at the moment they choose.
 */
export function advice(bytes: number): { archive: boolean; reason: string } {
  const gb = bytes / 1024 ** 3;
  if (gb >= 2) {
    return {
      archive: false,
      reason: "Use the download script — an archive this size is not a browser download.",
    };
  }
  if (gb >= 0.25) {
    return { archive: true, reason: "Either works at this size; the script resumes if interrupted." };
  }
  return { archive: true, reason: "Small enough to take as a single archive." };
}

/** Failure codes as something a person can act on. */
export function explainFailure(code: string | null, detail: string | null): string {
  switch (code) {
    case "BYTE_LIMIT_EXCEEDED":
      return "This export produced more data than it was admitted for. Narrow the time range or the pod pattern and try again.";
    case "UPSTREAM_FAILED":
      return "Loki stopped responding while this export was running. It is safe to retry.";
    case "STORAGE_FAILED":
      return "The export could not be written to storage. This one is for the platform team.";
    default:
      return detail ?? "This export stopped unexpectedly.";
  }
}

/**
 * How long the rest of an export will take, extrapolated from the part of it
 * that has already run.
 *
 * <p>Elapsed time is measured from submission, so a job that queued behind
 * others reads slower than it is running. That errs towards over-estimating,
 * which is the right way to be wrong about a wait.
 *
 * @returns seconds remaining, or null when there is not enough to judge by
 */
export function remainingSeconds(
  done: number,
  total: number,
  elapsedSeconds: number,
): number | null {
  if (done <= 0 || done >= total || elapsedSeconds <= 0) return null;
  return Math.round((elapsedSeconds / done) * (total - done));
}

/**
 * A duration rounded to whatever unit is honest at that scale. Nobody needs
 * seconds on a two-hour wait, and "0h" is worse than "under a minute".
 */
export function formatApprox(seconds: number): string {
  if (seconds < 60) return "under a minute";
  if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
  if (seconds < 86400) {
    const hours = seconds / 3600;
    // A trailing ".0" is noise on an approximation: "2 hours", not "2.0 hours".
    const shown = hours < 10 ? hours.toFixed(1).replace(/\.0$/, "") : String(Math.round(hours));
    return plural(shown, "hour");
  }
  return plural(Math.round(seconds / 86400), "day");
}

/** "1 day", not "1 days". A unit that disagrees with its number reads as a bug. */
function plural(count: number | string, unit: string): string {
  return `${count} ${unit}${Number(count) === 1 ? "" : "s"}`;
}

/** Time left on something, phrased so "about" is never put in front of "under". */
export function formatRemaining(seconds: number): string {
  return seconds < 60 ? "under a minute left" : `about ${formatApprox(seconds)} left`;
}

/** How long ago something happened, for timestamps nobody wants to read in full. */
export function formatAgo(seconds: number): string {
  if (seconds < 60) return "just now";
  return `${formatApprox(seconds)} ago`;
}

/** Average rate over the whole life of a job, or null when it is too early. */
export function formatRate(bytes: number, elapsedSeconds: number): string | null {
  if (bytes <= 0 || elapsedSeconds < 5) return null;
  return `${formatBytes(Math.round(bytes / elapsedSeconds))}/s`;
}

/** A fraction of an allowance, clamped so a bar cannot overflow its track. */
export function usedFraction(used: number, limit: number): number {
  if (limit <= 0) return 0;
  return Math.min(used / limit, 1);
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * A moment as the whole page writes it: 24 Sep 15:37, in the reader's time
 * zone. One short format everywhere, so a list of exports and the range they
 * came from read the same. The year is added only when it is not this one.
 */
export function formatWhen(when: string | Date, now: Date = new Date()): string {
  const date = typeof when === "string" ? new Date(when) : when;
  const pad = (n: number) => String(n).padStart(2, "0");
  const day = `${date.getDate()} ${MONTHS[date.getMonth()]}`;
  const year = date.getFullYear() === now.getFullYear() ? "" : ` ${date.getFullYear()}`;
  return `${day}${year} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** A range as its start and its length: 24 Sep 15:37 · 1d. */
export function formatSpan(from: string, to: string, now: Date = new Date()): string {
  const seconds = Math.round((new Date(to).getTime() - new Date(from).getTime()) / 1000);
  return `${formatWhen(from, now)} · ${formatDuration(seconds)}`;
}
