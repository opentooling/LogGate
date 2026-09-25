import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type ExportJob, type Me, type Quota } from "./api";
import { NewExport } from "./NewExport";
import { Exports } from "./Exports";
import { Activity } from "./Activity";
import { Audit } from "./Audit";
import { applyTheme, nextTheme, rememberTheme, storedTheme, themeLabel, type Theme } from "./theme";

type View = "export" | "activity" | "audit";

/** The view named in the address, so a link to the dashboard opens it. */
function viewFromHash(): View {
  const hash = window.location.hash.slice(1);
  return hash === "activity" || hash === "audit" ? hash : "export";
}

/** States that are still moving, and therefore worth polling. */
export const ACTIVE_STATES = new Set(["QUEUED", "PLANNED", "RUNNING", "FINALIZING"]);

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [quota, setQuota] = useState<Quota | null>(null);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [theme, setTheme] = useState<Theme>(storedTheme);
  const [view, setView] = useState<View>(viewFromHash);

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
    Promise.all([api.me().then(setMe).catch(() => {}), refresh()]).finally(() =>
      setLoading(false),
    );
  }, [refresh]);

  // Poll only while something is running, so an idle page stays quiet.
  useEffect(() => {
    if (!jobs.some((job) => ACTIVE_STATES.has(job.state))) return;
    const timer = setInterval(refresh, 2000);
    return () => clearInterval(timer);
  }, [jobs, refresh]);

  async function signOut() {
    try {
      const { redirect } = await api.signOut();
      window.location.assign(redirect);
    } catch {
      // The session may already be gone; the signed-out page says the same.
      window.location.assign("/signed-out.html");
    }
  }

  function cycleTheme() {
    const chosen = nextTheme(theme);
    setTheme(chosen);
    applyTheme(chosen);
    rememberTheme(chosen);
  }

  return (
    <div className="page">
      <header className="masthead">
        <div>
          <h1>LogGate</h1>
          <p className="tagline">
            Bulk log export, for when a dashboard is the wrong tool.
          </p>
        </div>
        {/* The other views are for administrators; everyone else has only
            the one, so there is nothing to switch between. */}
        {me?.admin && (
          <nav className="tabs" aria-label="Views">
            <button type="button" aria-pressed={view === "export"} onClick={() => show("export")}>
              Export
            </button>
            <button type="button" aria-pressed={view === "activity"} onClick={() => show("activity")}>
              Activity
            </button>
            <button type="button" aria-pressed={view === "audit"} onClick={() => show("audit")}>
              Audit
            </button>
          </nav>
        )}
        <div className="who">
          <a className="link" href="/guide" target="_blank" rel="noopener">
            Guide
          </a>
          <button type="button" className="theme" onClick={cycleTheme} aria-live="polite">
            {themeLabel(theme)}
          </button>
          {me && (
            <>
              <span className="name">{me.name}</span>
              <button type="button" className="link sign-out" onClick={signOut}>
                Sign out
              </button>
            </>
          )}
        </div>
      </header>

      {error && (
        <div className="banner banner-error" role="alert">
          <span>{error}</span>
          <button type="button" className="ghost" onClick={() => setError(null)}>
            Dismiss
          </button>
        </div>
      )}

      {loading ? (
        <p className="quiet">Loading…</p>
      ) : view === "activity" && me?.admin ? (
        <Activity onError={setError} />
      ) : view === "audit" && me?.admin ? (
        <Audit onError={setError} />
      ) : (
        <div className="columns">
          <div>
            {me && me.barrier ? (
              <section className="card empty" data-testid="barrier">
                <h2>{me.mode === "OPEN" ? "No access" : "No namespaces"}</h2>
                <p>{me.barrier}</p>
              </section>
            ) : (
              me && (
                <NewExport me={me} quota={quota} onSubmitted={refresh} onError={setError} />
              )
            )}
          </div>
          <Exports jobs={jobs} onChanged={refresh} onError={setError} />
        </div>
      )}
    </div>
  );
}
