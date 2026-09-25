import { describe, expect, it } from "vitest";
import { bucketLabel, failureLabel, niceMax, rankFailures } from "../charts";

describe("niceMax", () => {
  it("rounds up to 1, 2 or 5 times a power of ten", () => {
    expect(niceMax(7316)).toBe(10_000);
    expect(niceMax(1500)).toBe(2000);
    expect(niceMax(4)).toBe(5);
    expect(niceMax(5)).toBe(5);
    expect(niceMax(0.3)).toBe(0.5);
    expect(niceMax(1)).toBe(1);
  });

  it("gives an empty chart an axis of one", () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(Number.NaN)).toBe(1);
    expect(niceMax(-3)).toBe(1);
  });
});

describe("bucketLabel", () => {
  it("shows the hour for hourly buckets and the day for daily ones", () => {
    const at = "2026-09-20T14:00:00Z";
    expect(bucketLabel(at, 3600, "en-GB")).toMatch(/^\d{2}:00$/);
    expect(bucketLabel(at, 86_400, "en-GB")).toMatch(/Sept? 2[01]|2[01] Sept?/);
  });
});

describe("failures", () => {
  it("names each failure code plainly, and unknown ones readably", () => {
    expect(failureLabel("BYTE_LIMIT_EXCEEDED")).toBe("Grew past its size cap");
    expect(failureLabel("SOMETHING_NEW")).toBe("something new");
  });

  it("ranks the most common first, ties by name", () => {
    expect(rankFailures({ B: 1, A: 1, C: 5 })).toEqual([
      ["C", 5],
      ["A", 1],
      ["B", 1],
    ]);
  });
});
