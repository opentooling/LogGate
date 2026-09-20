/** Types mirroring the API, and the fetch wrapper every call goes through. */

export type Namespace = { name: string; team: string; owningGroup: string };

export type Me = {
  subject: string;
  name: string;
  groups: string[];
  namespaces: Namespace[];
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
  progress: number;
};

export type Estimate = {
  selector: string;
  from: string;
  to: string;
  estimatedBytes: number;
  bytesByNamespace: Record<string, number>;
  windowSeconds: number;
  windowCount: number;
};

export type Download = {
  name: string;
  key: string;
  sizeBytes: number;
  sha256: string | null;
  url: string;
};

export type ExportRequest = {
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
        .map(([namespace, reason]) => `${namespace} (${reason.toLowerCase().replaceAll("_", " ")})`)
        .join(", ");
      return `You are not allowed to export ${denied}.`;
    }
  }
  return `The request failed (${status}).`;
}

export const api = {
  me: () => call<Me>("/api/me"),
  estimate: (request: ExportRequest) =>
    call<Estimate>("/api/exports/estimate", {
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

/** Bytes as something a person can read at a glance. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/** A duration in whole units, for window sizes. */
export function formatDuration(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600}h`;
  if (seconds % 60 === 0) return `${seconds / 60}m`;
  return `${seconds}s`;
}
