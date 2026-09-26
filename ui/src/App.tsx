import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type ExportJob, type Me, type Quota } from "./api";
import { QueryBar } from "./QueryBar";
import { ExportsTable } from "./ExportsTable";
import { Activity } from "./Activity";
import { Audit } from "./Audit";
import { ACTIVE, draftFrom, type Draft } from "./jobs";
import { applyTheme, nextTheme, rememberTheme, storedTheme, themeLabel, type Theme } from "./theme";

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

  const onError = useCallback((message: string) => setError(message || null), []);

  return (
    <div className="shell">
      <aside className="rail">
        <a className="brand" href="/" aria-label="LogGate">
          <span className="mark" aria-hidden="true">
            L
          </span>
          <span>LogGate</span>
        </a>
        <nav className="rail-nav" aria-label="Views">
          <button type="button" aria-pressed={view === "export"} onClick={() => show("export")}>
            Export
          </button>
          {/* Across everyone's exports, so for administrators only. */}
          {me?.admin && (
            <>
              <button type="button" aria-pressed={view === "activity"} onClick={() => show("activity")}>
                Activity
              </button>
              <button type="button" aria-pressed={view === "audit"} onClick={() => show("audit")}>
                Audit
              </button>
            </>
          )}
          <a href="/guide" target="_blank" rel="noopener">
            Guide
          </a>
        </nav>
        <div className="rail-account">
          {me && (
            <span className="who" title={me.subject}>
              <span className="avatar" aria-hidden="true">
                {me.name.slice(0, 1).toUpperCase()}
              </span>
              <span className="name">{me.name}</span>
            </span>
          )}
          <button type="button" className="text-button" onClick={cycleTheme} aria-live="polite">
            {themeLabel(theme)}
          </button>
          {me && (
            <button type="button" className="text-button" onClick={signOut}>
              Sign out
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
