/**
 * Light and dark, chosen explicitly or left to the operating system.
 *
 * <p>Following the system is the right default, but it is not always the right
 * answer: a dark terminal next to a light browser is a common way to work, and
 * so is reading logs on a projector. The choice is kept per browser, because it
 * is a property of the screen in front of someone rather than of their account.
 */

export type Theme = "system" | "light" | "dark";

const KEY = "loggate.theme";
const ORDER: Theme[] = ["system", "light", "dark"];

function isTheme(value: string | null): value is Theme {
  return value === "system" || value === "light" || value === "dark";
}

/** The stored choice, or following the system when there is none. */
export function storedTheme(read: () => string | null = () => safeGet()): Theme {
  const value = read();
  return isTheme(value) ? value : "system";
}

/** The next choice in the cycle, for a single control with three states. */
export function nextTheme(current: Theme): Theme {
  return ORDER[(ORDER.indexOf(current) + 1) % ORDER.length]!;
}

/** What the control should say it is doing now. */
export function themeLabel(theme: Theme): string {
  return theme === "system" ? "System theme" : theme === "light" ? "Light" : "Dark";
}

/** The part of an element this needs, so applying a theme is testable alone. */
export type ThemeTarget = Pick<HTMLElement, "setAttribute" | "removeAttribute">;

/**
 * Applies a choice to the document. "system" removes the attribute rather than
 * resolving it, so the page keeps tracking the system if it changes while open.
 */
export function applyTheme(
  theme: Theme,
  root: ThemeTarget = document.documentElement,
): void {
  if (theme === "system") {
    root.removeAttribute("data-theme");
  } else {
    root.setAttribute("data-theme", theme);
  }
}

/** Storing a preference must never be the reason a page fails to load. */
export function rememberTheme(theme: Theme): void {
  try {
    window.localStorage.setItem(KEY, theme);
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
