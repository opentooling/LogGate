import { describe, expect, it } from "vitest";
import { storedCollapsed } from "../rail";

describe("storedCollapsed", () => {
  it("opens the rail when nothing has been chosen", () => {
    expect(storedCollapsed(() => null)).toBe(false);
  });

  it("ignores a stored value it does not know", () => {
    expect(storedCollapsed(() => "sideways")).toBe(false);
  });

  it("honours a rail that was folded", () => {
    expect(storedCollapsed(() => "collapsed")).toBe(true);
  });
});
