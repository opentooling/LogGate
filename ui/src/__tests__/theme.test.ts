import { describe, expect, it } from "vitest";
import { applyTheme, nextTheme, storedTheme, themeLabel, type ThemeTarget } from "../theme";

/** Just enough of an element to see what applying a theme does to one. */
function target(initial?: string): ThemeTarget & { value: string | undefined } {
  return {
    value: initial,
    setAttribute(_name: string, value: string) {
      this.value = value;
    },
    removeAttribute() {
      this.value = undefined;
    },
  };
}

describe("storedTheme", () => {
  it("follows the system when nothing has been chosen", () => {
    expect(storedTheme(() => null)).toBe("system");
  });

  it("ignores a stored value that is not a theme", () => {
    // Storage is shared with whatever else the browser holds, and a corrupt
    // value must not leave the page with no colours at all.
    expect(storedTheme(() => "chartreuse")).toBe("system");
  });

  it("honours a choice that was made", () => {
    expect(storedTheme(() => "light")).toBe("light");
  });
});

describe("nextTheme", () => {
  it("cycles and comes back round", () => {
    expect(nextTheme("system")).toBe("light");
    expect(nextTheme("light")).toBe("dark");
    expect(nextTheme("dark")).toBe("system");
  });
});

describe("applyTheme", () => {
  it("marks an explicit choice on the document", () => {
    const root = target();
    applyTheme("dark", root);
    expect(root.value).toBe("dark");
  });

  it("removes the mark for the system, so the page keeps tracking it", () => {
    const root = target("light");
    applyTheme("system", root);
    expect(root.value).toBeUndefined();
  });
});

describe("themeLabel", () => {
  it("says what the control is doing now", () => {
    expect(themeLabel("system")).toBe("System theme");
    expect(themeLabel("light")).toBe("Light");
    expect(themeLabel("dark")).toBe("Dark");
  });
});
