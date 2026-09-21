/**
 * The server's idea of "now", as seen from the browser.
 *
 * <p>"Submitted 4 min ago", "about 10 min left" and "deleted in 2 days" all
 * compare a timestamp the server wrote with the current time. Using the
 * browser's clock for the second half makes them wrong by however far the two
 * clocks disagree, and a laptop hours out is not unusual. Every response
 * carries the server's Date header, so the offset is observed on each call and
 * relative times are computed on the server's clock instead.
 *
 * <p>The header has one-second resolution, which is far finer than anything
 * shown with it.
 */

let offsetMillis = 0;

/** Records the server's clock from a response's Date header, if it has one. */
export function observeServerDate(header: string | null, receivedAt: number = Date.now()): void {
  if (!header) return;
  const server = Date.parse(header);
  if (Number.isNaN(server)) return;
  offsetMillis = server - receivedAt;
}

/** Now, by the server's clock. */
export function serverNow(localNow: number = Date.now()): number {
  return localNow + offsetMillis;
}

/** For tests: forget what has been observed. */
export function resetServerClock(): void {
  offsetMillis = 0;
}
