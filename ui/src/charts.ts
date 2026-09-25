/** Arithmetic for the activity charts, kept out of the components to be tested. */

/**
 * A round number at or above {@code value}, for the top of an axis: 1, 2 or
 * 5 times a power of ten. An axis topped at 7,316 reads worse than one at
 * 10,000, and says nothing more.
 */
export function niceMax(value: number): number {
  if (!(value > 0)) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  for (const step of [1, 2, 5, 10]) {
    if (step * power >= value) return step * power;
  }
  return 10 * power;
}

/** A bucket's label: the hour for sub-day buckets, the day otherwise. */
export function bucketLabel(at: string, bucketSeconds: number, locale?: string): string {
  const date = new Date(at);
  return bucketSeconds < 86_400
    ? date.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" })
    : date.toLocaleDateString(locale, { month: "short", day: "numeric" });
}

/** What each failure code means, in the words a user would use. */
const FAILURES: Record<string, string> = {
  BYTE_LIMIT_EXCEEDED: "Grew past its size cap",
  UPSTREAM_FAILED: "Loki kept failing",
  STORAGE_FAILED: "Could not write to storage",
  UNKNOWN: "No reason recorded",
};

export function failureLabel(code: string): string {
  return FAILURES[code] ?? code.toLowerCase().replaceAll("_", " ");
}

/** Failure codes by count, most common first. */
export function rankFailures(failures: Record<string, number>): [string, number][] {
  return Object.entries(failures).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
}
