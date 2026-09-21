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
  namespaces: Namespace[];
  /** Why the caller can export nothing, or null when they can. */
  barrier: string | null;
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

export type ExportRequest = {
  clusters?: string[];
  namespaces: string[];
  podPattern?: string;
  containerPattern?: string;
  lineFilter?: string;
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
