import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type ExportJob, type Me, type Quota } from "./api";
import { NewExport } from "./NewExport";
import { Exports } from "./Exports";
import { applyTheme, nextTheme, rememberTheme, storedTheme, themeLabel, type Theme } from "./theme";

/** States that are still moving, and therefore worth polling. */
export const ACTIVE_STATES = new Set(["QUEUED", "PLANNED", "RUNNING", "FINALIZING"]);

export function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [quota, setQuota] = useState<Quota | null>(null);
  const [jobs, setJobs] = useState<ExportJob[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [theme, setTheme] = useState<Theme>(storedTheme);

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
        <div className="who">
          <button type="button" className="theme" onClick={cycleTheme} aria-live="polite">
            {themeLabel(theme)}
          </button>
          {me && (
            <>
              <span className="name">{me.name}</span>
              <a className="link" href="/logout">
                Sign out
              </a>
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
      ) : (
        <div className="columns">
          <div>
            {me && me.namespaces.length === 0 ? (
              <section className="card empty">
                <h2>No namespaces</h2>
                <p>
                  You are not in any group that owns a labelled namespace, so there is nothing you
                  can export. Ask the team that owns the namespace to add you to its group.
                </p>
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
