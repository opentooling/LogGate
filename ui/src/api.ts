/** Types mirroring the API, and the fetch wrapper every call goes through. */

import { observeServerDate } from "./clock";

/** A namespace on offer. Team and group are only known in team-label mode. */
export type Namespace = { name: string; team: string | null; owningGroup: string | null };

export type AccessMode = "TEAM_LABEL" | "OPEN";

export type Me = {
  subject: string;
  name: string;
  groups: string[];
  mode: AccessMode;
  /** Whether an export may name no namespaces, meaning every one. */
  namespacesOptional: boolean;
  /** Clusters on offer; empty when logs are not told apart by cluster. */
  clusters: string[];
  /**
   * Namespaces on offer. Empty in open mode with clusters to choose from:
   * those are listed per cluster, once clusters are chosen.
   */
  namespaces: Namespace[];
  /** Why the caller can export nothing, or null when they can. */
  barrier: string | null;
  /** Whether pods can be picked from a list. */
  podsListable: boolean;
  /** Whether pods may be matched by pattern; an operator can switch it off. */
  podPatternAllowed: boolean;
  /** Whether the caller may see the Activity and Audit pages. */
  admin: boolean;
};

export type DownloadAction = "DOWNLOAD_LINKS_ISSUED" | "DOWNLOAD_SCRIPT_ISSUED" | "ARCHIVE_DOWNLOADED";

/** One hand-over of an export's data, as the audit trail recorded it. */
export type DownloadEvent = {
  id: number;
  at: string;
  subject: string;
  name: string;
  action: DownloadAction;
  jobId: string | null;
  namespaces: string[];
  clusters: string[];
  files: number | null;
  bytes: number | null;
  sourceIp: string | null;
};

export type DownloadAudit = {
  page: { events: DownloadEvent[]; next: number | null };
  totals: Partial<Record<DownloadAction, number>>;
};

export type Pod = { namespace: string; name: string };

/** The pods that ran in some namespaces over some range. */
export type PodListing = { available: boolean; pods: Pod[]; truncated: boolean };

export type ActivityPeriod = "24h" | "7d" | "30d";

export type ActivityPoint = {
  at: string;
  submitted: number;
  refused: number;
  ready: number;
  failed: number;
  cancelled: number;
  bytesExported: number;
};

/** How exporting has gone across the installation. Counts only. */
export type ActivityReport = {
  from: string;
  to: string;
  bucketSeconds: number;
  now: { activeExports: number; windowsPending: number; windowsRunning: number };
  totals: {
    submitted: number;
    refused: number;
    denied: number;
    ready: number;
    failed: number;
    cancelled: number;
    bytesExported: number;
    entriesExported: number;
  };
  failures: Record<string, number>;
  durationSeconds: { p50: number | null; p95: number | null; max: number | null };
  series: ActivityPoint[];
};

export type JobState =
  | "QUEUED"
  | "PLANNED"
  | "RUNNING"
  | "FINALIZING"
  | "READY"
  | "EXPIRED"
  | "CANCELLED"
  | "FAILED";

export type ExportJob = {
  id: string;
  state: JobState;
  failureCode: string | null;
  failureDetail: string | null;
  namespaces: string[];
  clusters: string[];
  selector: string;
  from: string;
  to: string;
  estimatedBytes: number;
  byteLimit: number;
  windowsTotal: number;
  windowsDone: number;
  bytesWritten: number;
  entriesWritten: number;
  cancelRequested: boolean;
  createdAt: string;
  finishedAt: string | null;
  expiresAt: string | null;
  progress: number;
  /**
   * Whether the caller may take its files now. False for a finished export
   * whose namespaces the caller can no longer read, for instance one made in
   * another access mode.
   */
  downloadable: boolean;
  /** What its files hold. */
  format: OutputFormat;
};

export type Estimate = {
  selector: string;
  from: string;
  to: string;
  estimatedBytes: number;
  filteredBytes: number | null;
  bytesByNamespace: Record<string, number>;
  windowSeconds: number;
  windowCount: number;
};

/** What quota would say about an export, alongside what it would cost. */
export type Admission = { allowed: boolean; reason: string | null; byteLimit: number };

export type Sizing = { estimate: Estimate; admission: Admission };

/** A budget spent from: a team's, or in open mode the caller's own. */
export type Budget = { holder: string; label: string; usedBytes: number; limitBytes: number };

/** The limits an export is judged against, and what is already spent. */
export type Quota = {
  maxRangeSeconds: number;
  maxEstimatedBytes: number;
  concurrentPerUser: number;
  yourActiveExports: number;
  concurrentGlobal: number;
  activeExports: number;
  budgetWindowSeconds: number;
  retentionSeconds: number;
  budgets: Budget[];
};

export type Download = {
  name: string;
  key: string;
  sizeBytes: number;
  sha256: string | null;
  url: string;
};

/** What an export's files hold. */
export type OutputFormat = "JSON" | "RAW";

export type ExportRequest = {
  clusters?: string[];
  namespaces: string[];
  /** Pods picked by name; not with podPattern. */
  pods?: string[];
  podPattern?: string;
  containerPattern?: string;
  lineFilter?: string;
  format?: OutputFormat;
  from: string;
  to: string;
};

/** An API call that failed, carrying whatever the server explained. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

function csrfToken(): string | undefined {
  // The backend issues this cookie on every response; echoing it back is what
  // proves a write came from this page rather than another origin.
  return document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = csrfToken();
  const response = await fetch(path, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}),
      ...init.headers,
    },
  });
  observeServerDate(response.headers.get("Date"));

  if (response.status === 401) {
    // The session has gone; start the login again rather than showing an error
    // the user cannot act on.
    window.location.href = "/oauth2/authorization/keycloak";
    throw new ApiError(401, "signing in");
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(response.status, explain(response.status, body));
  }
  if (response.status === 204 || response.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Turns an error body into something worth reading. */
function explain(status: number, body: unknown): string {
  if (body && typeof body === "object") {
    const record = body as Record<string, unknown>;
    if (typeof record.message === "string") return record.message;
    if (record.denied && typeof record.denied === "object") {
      const denied = Object.entries(record.denied as Record<string, string>)
        .map(([key, reason]) => {
          const what = key.startsWith("cluster/") ? `cluster ${key.slice("cluster/".length)}` : key;
          return `${what} (${reason.toLowerCase().replaceAll("_", " ")})`;
        })
        .join(", ");
      return `You are not allowed to export ${denied}.`;
    }
  }
  return `The request failed (${status}).`;
}

export const api = {
  me: () => call<Me>("/api/me"),
  quota: () => call<Quota>("/api/quota"),
  namespaces: (clusters: string[]) =>
    call<Namespace[]>(
      "/api/namespaces" +
        (clusters.length ? "?" + clusters.map((c) => `cluster=${encodeURIComponent(c)}`).join("&") : ""),
    ),
  pods: (clusters: string[], namespaces: string[], from: string, to: string) =>
    call<PodListing>(
      "/api/pods?" +
        new URLSearchParams([
          ...clusters.map((c): [string, string] => ["cluster", c]),
          ...namespaces.map((n): [string, string] => ["namespace", n]),
          ["from", from],
          ["to", to],
        ]).toString(),
    ),
  /**
   * Signs out of LogGate and of the identity provider. Answered with where to
   * go next, the provider's end-session page, because a fetch cannot follow a
   * redirect to another origin itself.
   */
  signOut: () =>
    call<{ redirect: string }>("/logout", {
      method: "POST",
      headers: { Accept: "application/json" },
    }),
  downloadAudit: (before: number | null, limit = 50) =>
    call<DownloadAudit>(
      `/api/audit/downloads?limit=${limit}` + (before === null ? "" : `&before=${before}`),
    ),
  activity: (period: ActivityPeriod) =>
    call<ActivityReport>(`/api/activity?period=${encodeURIComponent(period)}`),
  estimate: (request: ExportRequest) =>
    call<Sizing>("/api/exports/estimate", {
      method: "POST",
      body: JSON.stringify(request),
    }),
  submit: (request: ExportRequest) =>
    call<ExportJob>("/api/exports", { method: "POST", body: JSON.stringify(request) }),
  list: () => call<ExportJob[]>("/api/exports"),
  get: (id: string) => call<ExportJob>(`/api/exports/${id}`),
  cancel: (id: string) => call<void>(`/api/exports/${id}/cancel`, { method: "POST" }),
  downloads: (id: string) => call<Download[]>(`/api/exports/${id}/downloads`),
};
