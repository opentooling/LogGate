import { describe, expect, it } from "vitest";
import {
  advice,
  explainFailure,
  formatAgo,
  formatApprox,
  formatBytes,
  formatCount,
  formatDuration,
  formatRate,
  formatRemaining,
  remainingSeconds,
  usedFraction,
} from "../format";

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

describe("remainingSeconds", () => {
  it("extrapolates from the part that has already run", () => {
    // Four of ten windows in 40s means 6s per window, so 36s left.
    expect(remainingSeconds(4, 10, 40)).toBe(60);
  });

  it("says nothing before there is anything to judge by", () => {
    expect(remainingSeconds(0, 10, 40)).toBeNull();
    expect(remainingSeconds(4, 10, 0)).toBeNull();
  });

  it("says nothing once there is no work left", () => {
    expect(remainingSeconds(10, 10, 40)).toBeNull();
  });
});

describe("formatApprox", () => {
  it("rounds to the unit that is honest at that scale", () => {
    expect(formatApprox(30)).toBe("under a minute");
    expect(formatApprox(150)).toBe("3 min");
    expect(formatApprox(5400)).toBe("1.5 hours");
    expect(formatApprox(48 * 3600)).toBe("2 days");
  });

  it("agrees with its own number, which a unit that does not reads as a bug", () => {
    expect(formatApprox(86400)).toBe("1 day");
    expect(formatApprox(3600)).toBe("1 hour");
  });

  it("drops the decimal on longer waits, where it is noise", () => {
    expect(formatApprox(12 * 3600)).toBe("12 hours");
    expect(formatApprox(7200)).toBe("2 hours");
  });
});

describe("formatAgo", () => {
  it("does not pretend to a precision it does not have", () => {
    expect(formatAgo(5)).toBe("just now");
    expect(formatAgo(300)).toBe("5 min ago");
  });
});

describe("formatRate", () => {
  it("reports an average once there is enough of one", () => {
    expect(formatRate(10 * 1024 ** 2, 10)).toBe("1.0 MB/s");
  });

  it("stays quiet while a rate would be meaningless", () => {
    expect(formatRate(1024, 2)).toBeNull();
    expect(formatRate(0, 60)).toBeNull();
  });
});

describe("usedFraction", () => {
  it("is a fraction of the allowance", () => {
    expect(usedFraction(50, 200)).toBe(0.25);
  });

  it("never overflows its track, however much was spent", () => {
    expect(usedFraction(300, 200)).toBe(1);
  });

  it("treats a budget that is switched off as nothing spent", () => {
    expect(usedFraction(300, 0)).toBe(0);
  });
});

describe("formatRemaining", () => {
  it("never says 'about under a minute'", () => {
    expect(formatRemaining(20)).toBe("under a minute left");
    expect(formatRemaining(600)).toBe("about 10 min left");
  });
});
