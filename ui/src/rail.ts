/**
 * Whether the rail is folded down to its icons. Kept per browser, like the
 * theme: it is a property of the screen in front of someone, a narrow laptop
 * or a wide monitor, rather than of their account.
 */

const KEY = "loggate.rail";

/** The stored choice; open when there is none. */
export function storedCollapsed(read: () => string | null = () => safeGet()): boolean {
  return read() === "collapsed";
}

/** Storing a preference must never be the reason a page fails to load. */
export function rememberCollapsed(collapsed: boolean): void {
  try {
    window.localStorage.setItem(KEY, collapsed ? "collapsed" : "open");
  } catch {
    // Private browsing, or storage denied. The choice lasts this visit only.
  }
}

function safeGet(): string | null {
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}
