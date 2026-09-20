import { describe, expect, it } from "vitest";
import { advice, explainFailure, formatBytes, formatCount, formatDuration } from "../format";

describe("formatBytes", () => {
  it("keeps small sizes exact", () => {
    expect(formatBytes(512)).toBe("512 B");
  });

  it("steps up through the units", () => {
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(5 * 1024 ** 3)).toBe("5.0 GB");
  });

  it("drops the decimal once the number is large enough not to need it", () => {
    expect(formatBytes(40 * 1024 ** 3)).toBe("40 GB");
  });
});

describe("formatDuration", () => {
  it("describes ranges in the units people ask for them in", () => {
    expect(formatDuration(3600)).toBe("1h");
    expect(formatDuration(48 * 3600)).toBe("2d");
    expect(formatDuration(5400)).toBe("1h 30m");
  });

  it("handles a range that is not really a range", () => {
    expect(formatDuration(0)).toBe("0s");
  });
});

describe("formatCount", () => {
  it("abbreviates large counts", () => {
    expect(formatCount(950)).toBe("950");
    expect(formatCount(19_070_146)).toBe("19M");
  });
});

describe("advice", () => {
  it("steers a large export away from the browser", () => {
    // People will click the zip unless told otherwise at the moment they choose.
    const large = advice(30 * 1024 ** 3);
    expect(large.archive).toBe(false);
    expect(large.reason).toContain("download script");
  });

  it("is relaxed about a small one", () => {
    expect(advice(10 * 1024 ** 2).archive).toBe(true);
  });
});

describe("explainFailure", () => {
  it("turns a quota failure into something the user can act on", () => {
    expect(explainFailure("BYTE_LIMIT_EXCEEDED", null)).toContain("Narrow the time range");
  });

  it("says plainly when a failure is not the user's to fix", () => {
    expect(explainFailure("STORAGE_FAILED", null)).toContain("platform team");
  });

  it("falls back to whatever detail there is", () => {
    expect(explainFailure(null, "something specific")).toBe("something specific");
  });
});
