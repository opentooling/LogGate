import { describe, expect, it } from "vitest";
import { PRESETS, durationSeconds, rangeFor, toLocalInput } from "../ranges";

describe("presets", () => {
  it("offers bulk-sized ranges only", () => {
    // Offering minutes would invite people to use this where a Grafana query
    // belongs, which is the boundary the whole product depends on.
    expect(Math.min(...PRESETS.map((p) => p.seconds))).toBeGreaterThanOrEqual(3600);
  });

  it("produces a range that ends now and starts earlier", () => {
    const { from, to } = rangeFor(PRESETS[0]!);
    expect(durationSeconds(from, to)).toBeGreaterThan(0);
  });
});

describe("durationSeconds", () => {
  it("measures a valid range", () => {
    expect(durationSeconds("2026-09-20T00:00", "2026-09-20T06:00")).toBe(6 * 3600);
  });

  it("treats a reversed or empty range as nothing", () => {
    expect(durationSeconds("2026-09-20T06:00", "2026-09-20T00:00")).toBe(0);
    expect(durationSeconds("", "")).toBe(0);
  });
});

describe("toLocalInput", () => {
  it("formats for a datetime-local input", () => {
    expect(toLocalInput(new Date(2026, 8, 20, 9, 5))).toBe("2026-09-20T09:05");
  });
});
