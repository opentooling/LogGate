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
  if (!parts.length) parts.push(`${seconds}s`);
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
