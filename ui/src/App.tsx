import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type ExportJob, type Me, type Quota } from "./api";
import { QueryBar } from "./QueryBar";
import { ExportsTable } from "./ExportsTable";
import { Activity } from "./Activity";
import { Audit } from "./Audit";
import { ACTIVE, draftFrom, type Draft } from "./jobs";
import { applyTheme, nextTheme, rememberTheme, storedTheme, themeLabel, type Theme } from "./theme";
import { rememberCollapsed, storedCollapsed } from "./rail";

/** Line icons for the rail, which is all it shows when folded. */
const ICONS = {
  export: "M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10",
  activity: "M1.5 8.5h3l2-5 3 9 2-4h3",
  audit: "M4 2.5h8v11H4zM6 5.5h4M6 8h4M6 10.5h2",
  guide: "M2.5 3.5H6a2 2 0 0 1 2 2v8a1.5 1.5 0 0 0-1.5-1.5h-4zM13.5 3.5H10a2 2 0 0 0-2 2v8a1.5 1.5 0 0 1 1.5-1.5h4z",
  theme: "M8 2.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11zM8 2.5v11",
  signOut: "M6.5 2.5h-4v11h4M10 5l3 3-3 3M13 8H6",
  fold: "M10 4 6 8l4 4",
  unfold: "M6 4l4 4-4 4",
} as const;

function Icon({ name }: { name: keyof typeof ICONS }) {
  return (
    <svg className="icon" viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
      <path d={ICONS[name]} />
    </svg>
  );
}

type View = "export" | "activity" | "audit";

/** The view named in the address, so a link to the dashboard opens it. */
function viewFromHash(): View {
  const hash = window.location.hash.slice(1);
  return hash === "activity" || hash === "audit" ? hash : "export";
}

/** States that are still moving, and therefore worth polling. */
export const ACTIVE_STATES = ACTIVE;

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [quota, setQuota] = useState<Quota | null>(null);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [theme, setTheme] = useState<Theme>(storedTheme);
  const [view, setView] = useState<View>(viewFromHash);
  const [collapsed, setCollapsed] = useState(storedCollapsed);
  const [draft, setDraft] = useState<(Draft & { nonce: number }) | null>(null);

  useEffect(() => {
    const follow = () => setView(viewFromHash());
    window.addEventListener("hashchange", follow);
    return () => window.removeEventListener("hashchange", follow);
  }, []);

  function show(next: View) {
    window.location.hash = next === "export" ? "" : next;
    setView(next);
  }

  const refresh = useCallback(async () => {
    try {
      setJobs(await api.list());
      // Spending moves as jobs run, so the allowance is read with them rather
      // than once at load, where it would quietly go stale.
      setQuota(await api.quota());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    Promise.all([api.me().then(setMe).catch(() => {}), refresh()]).finally(() => setLoading(false));
  }, [refresh]);

  // Poll only while something is running, so an idle page stays quiet.
  useEffect(() => {
    if (!jobs.some((job) => ACTIVE.has(job.state))) return;
    const timer = setInterval(refresh, 2000);
    return () => clearInterval(timer);
  }, [jobs, refresh]);

  async function signOut() {
    try {
      const { redirect } = await api.signOut();
      window.location.assign(redirect);
    } catch {
      // The session may already be gone; either way, back to the landing page.
      window.location.assign("/welcome");
    }
  }

  function cycleTheme() {
    const chosen = nextTheme(theme);
    setTheme(chosen);
    applyTheme(chosen);
    rememberTheme(chosen);
  }

  function toggleRail() {
    setCollapsed(!collapsed);
    rememberCollapsed(!collapsed);
  }

  const onError = useCallback((message: string) => setError(message || null), []);

  return (
    <div className={collapsed ? "shell shell-collapsed" : "shell"}>
      <aside className="rail">
        <div className="rail-head">
          <a className="brand" href="/" aria-label="LogGate">
            <span className="mark" aria-hidden="true">
              L
            </span>
            <span className="label">LogGate</span>
          </a>
          <button
            type="button"
            className="rail-toggle"
            onClick={toggleRail}
            aria-expanded={!collapsed}
            aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
            title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
          >
            <Icon name={collapsed ? "unfold" : "fold"} />
          </button>
        </div>
        <nav className="rail-nav" aria-label="Views">
          <button type="button" aria-pressed={view === "export"} onClick={() => show("export")} title="Export">
            <Icon name="export" />
            <span className="label">Export</span>
          </button>
          {/* Across everyone's exports, so for administrators only. */}
          {me?.admin && (
            <>
              <button
                type="button"
                aria-pressed={view === "activity"}
                onClick={() => show("activity")}
                title="Activity"
              >
                <Icon name="activity" />
                <span className="label">Activity</span>
              </button>
              <button type="button" aria-pressed={view === "audit"} onClick={() => show("audit")} title="Audit">
                <Icon name="audit" />
                <span className="label">Audit</span>
              </button>
            </>
          )}
          <a href="/guide" target="_blank" rel="noopener" title="Guide">
            <Icon name="guide" />
            <span className="label">Guide</span>
          </a>
        </nav>
        <div className="rail-account">
          {me && (
            // A long address is cut short here; the whole of it is in the tooltip.
            <span className="who" title={me.name === me.subject ? me.name : `${me.name} (${me.subject})`}>
              <span className="avatar" aria-hidden="true">
                {me.name.slice(0, 1).toUpperCase()}
              </span>
              <span className="name label">{me.name}</span>
            </span>
          )}
          <button
            type="button"
            className="text-button"
            onClick={cycleTheme}
            aria-live="polite"
            title={themeLabel(theme)}
          >
            <Icon name="theme" />
            <span className="label">{themeLabel(theme)}</span>
          </button>
          {me && (
            <button type="button" className="text-button" onClick={signOut} title="Sign out">
              <Icon name="signOut" />
              <span className="label">Sign out</span>
            </button>
          )}
        </div>
      </aside>

      <main className="main">
        {error && (
          <div className="banner banner-error" role="alert">
            <span>{error}</span>
            <button type="button" className="text-button" onClick={() => setError(null)}>
              Dismiss
            </button>
          </div>
        )}

        {loading ? (
          <p className="quiet">Loading…</p>
        ) : view === "activity" && me?.admin ? (
          <Activity onError={onError} />
        ) : view === "audit" && me?.admin ? (
          <Audit onError={onError} />
        ) : (
          <>
            {me && me.barrier ? (
              <section className="panel empty" data-testid="barrier">
                <h2>{me.mode === "OPEN" ? "No access" : "No namespaces"}</h2>
                <p>{me.barrier}</p>
              </section>
            ) : (
              me && (
                <QueryBar me={me} quota={quota} draft={draft} onSubmitted={refresh} onError={onError} />
              )
            )}
            <ExportsTable
              jobs={jobs}
              onChanged={refresh}
              onRunAgain={(job) => {
                setDraft({ ...draftFrom(job), nonce: Date.now() });
                window.scrollTo({ top: 0, behavior: "smooth" });
              }}
              onError={onError}
            />
          </>
        )}
      </main>
    </div>
  );
}
