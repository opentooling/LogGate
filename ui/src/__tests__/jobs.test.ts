import { describe, expect, it } from "vitest";
import type { ExportJob } from "../api";
import { draftFrom, inTab, matchesJob } from "../jobs";
import { formatSpan, formatWhen } from "../format";

function job(overrides: Partial<ExportJob>): ExportJob {
  return {
    id: "8b1f0c1e-0000-4000-8000-000000000001",
    state: "READY",
    failureCode: null,
    failureDetail: null,
    namespaces: ["platform-dev"],
    clusters: ["k3d-loggate"],
    selector: '{namespace="platform-dev"}',
    from: "2026-09-24T14:37:00Z",
    to: "2026-09-25T14:37:00Z",
    estimatedBytes: 1,
    byteLimit: 1,
    windowsTotal: 1,
    windowsDone: 1,
    bytesWritten: 1,
    entriesWritten: 1,
    cancelRequested: false,
    createdAt: "2026-09-25T14:37:00Z",
    finishedAt: null,
    expiresAt: null,
    progress: 1,
    downloadable: true,
    format: "JSON",
    ...overrides,
  };
}

describe("tabs", () => {
  const jobs = [job({ state: "RUNNING" }), job({ state: "READY" }), job({ state: "QUEUED" }), job({ state: "FAILED" })];

  it("puts what is still moving under Active and the rest under Finished", () => {
    expect(inTab(jobs, "active").map((j) => j.state)).toEqual(["RUNNING", "QUEUED"]);
    expect(inTab(jobs, "finished").map((j) => j.state)).toEqual(["READY", "FAILED"]);
    expect(inTab(jobs, "all")).toHaveLength(4);
  });
});

describe("search", () => {
  it("matches every word against anything the table shows", () => {
    const raw = job({ namespaces: ["checkout-prod"], clusters: ["edge-eu"], format: "RAW" });
    expect(matchesJob(raw, "")).toBe(true);
    expect(matchesJob(raw, "  CHECKOUT edge ")).toBe(true);
    expect(matchesJob(raw, "raw")).toBe(true);
    expect(matchesJob(raw, "8b1f0c1e")).toBe(true);
    expect(matchesJob(raw, "checkout platform")).toBe(false);
    expect(matchesJob(job({ namespaces: [] }), "every")).toBe(true);
    expect(matchesJob(job({ state: "FAILED", failureCode: "BYTE_LIMIT_EXCEEDED" }), "byte_limit")).toBe(true);
  });
});

describe("running again", () => {
  it("keeps the scope and format, and the length of the range", () => {
    expect(draftFrom(job({ format: "RAW" }))).toEqual({
      clusters: ["k3d-loggate"],
      namespaces: ["platform-dev"],
      format: "RAW",
      seconds: 86_400,
    });
  });

  it("never asks for a range shorter than a minute", () => {
    expect(draftFrom(job({ from: "2026-09-25T14:37:00Z", to: "2026-09-25T14:37:10Z" })).seconds).toBe(60);
  });
});

describe("dates", () => {
  const now = new Date("2026-09-25T12:00:00");

  it("writes one short format, with the year only when it is another", () => {
    expect(formatWhen(new Date("2026-09-24T15:37:00"), now)).toBe("24 Sep 15:37");
    expect(formatWhen(new Date("2025-01-02T09:05:00"), now)).toBe("2 Jan 2025 09:05");
  });

  it("writes a range as its start and its length", () => {
    const from = new Date("2026-09-24T15:37:00").toISOString();
    const to = new Date("2026-09-25T15:37:00").toISOString();
    expect(formatSpan(from, to, now)).toBe("24 Sep 15:37 · 1d");
  });
});
