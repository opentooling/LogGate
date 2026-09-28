/** Sorting, searching and repeating exports: the table's logic, apart from the table. */

import type { ExportJob, OutputFormat } from "./api";

/** States that are still moving. */
export const ACTIVE = new Set(["QUEUED", "PLANNED", "RUNNING", "FINALIZING"]);

export type Tab = "active" | "finished" | "all";

/** The exports a tab shows, newest first as they arrive. */
export function inTab(jobs: ExportJob[], tab: Tab): ExportJob[] {
  if (tab === "all") return jobs;
  return jobs.filter((job) => ACTIVE.has(job.state) === (tab === "active"));
}

/** Whether an export matches a search, on anything the table shows of it. */
export function matchesJob(job: ExportJob, search: string): boolean {
  const needle = search.trim().toLowerCase();
  if (!needle) return true;
  const haystack = [
    job.state,
    job.id,
    ...job.namespaces,
    ...job.clusters,
    job.namespaces.length === 0 ? "every namespace" : "",
    job.format === "RAW" ? "raw lines" : "json",
    job.failureCode ?? "",
  ]
    .join(" ")
    .toLowerCase();
  return needle.split(/\s+/).every((word) => haystack.includes(word));
}

/** What running an export again starts from: the same scope, over the same length of time, ending now. */
export type Draft = {
  clusters: string[];
  namespaces: string[];
  format: OutputFormat;
  seconds: number;
};

export function draftFrom(job: ExportJob): Draft {
  return {
    clusters: job.clusters,
    namespaces: job.namespaces,
    format: job.format,
    seconds: Math.max(60, Math.round((new Date(job.to).getTime() - new Date(job.from).getTime()) / 1000)),
  };
}
