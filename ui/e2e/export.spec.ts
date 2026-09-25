import { expect, test, type Page } from "@playwright/test";

const PASSWORD = process.env.DEMO_PASSWORD ?? "loggate";

/** Signs in through the real Keycloak login form. */
async function signIn(page: Page, username: string) {
  // Through the landing page, as a person arrives.
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in with single sign-on" }).click();
  await page.fill("#username", username);
  await page.fill("#password", PASSWORD);
  await page.click("#kc-form-login button[type=submit], #kc-login");
  await expect(page.getByRole("heading", { name: "LogGate" })).toBeVisible();
}

test.describe("LogGate", () => {
  test("alice is offered only her own namespace", async ({ page }) => {
    await signIn(page, "alice");

    const list = page.locator('[data-testid="namespaces"]');
    const namespaces = list.locator(".pill");
    await expect(namespaces).toHaveCount(1);
    await expect(namespaces.first()).toContainText("platform-dev");
    // payments-dev belongs to another team and must not appear at all.
    await expect(list).not.toContainText("payments-dev");
  });

  test("a time range preset sets the range and shows its duration", async ({ page }) => {
    await signIn(page, "alice");

    // The range starts as the first preset, and says so.
    await expect(page.getByRole("button", { name: "Last hour" })).toHaveAttribute("aria-pressed", "true");

    await page.getByRole("button", { name: "Last 6 hours" }).click();

    await expect(page.locator("legend", { hasText: "Time range" })).toContainText("6h");
    await expect(page.getByRole("button", { name: "Last 6 hours" })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("button", { name: "Last hour" })).toHaveAttribute("aria-pressed", "false");

    // A range edited by hand is no longer any preset.
    await page.getByLabel("From", { exact: true }).fill("2026-01-01T00:00");
    await expect(page.locator('.presets [aria-pressed="true"]')).toHaveCount(0);
  });

  test("the estimate is shown before committing, with the generated query", async ({ page }) => {
    await signIn(page, "alice");

    await page.getByRole("button", { name: "Estimate first" }).click();

    const estimate = page.locator(".estimate");
    await expect(estimate).toBeVisible();
    await expect(estimate.locator(".estimate-headline")).toContainText(/B|KB|MB|GB/);

    // The selector is generated, never typed, so it is worth showing.
    await page.getByText("What will be queried").click();
    await expect(estimate.locator("code")).toContainText('namespace="platform-dev"');
  });

  test("an export runs to completion and offers its files", async ({ page }) => {
    await signIn(page, "alice");

    // Narrowed to the small steady workload. Without this the range can cover
    // whatever bulk data happens to be in the cluster, and the test measures
    // the machine rather than the behaviour.
    await page.getByRole("button", { name: "Match a pattern" }).click();
    await page.getByLabel("Pod pattern").fill("platform-api-*");

    await page.getByRole("button", { name: "Start export" }).click();

    const job = page.locator('[data-testid="job"]').first();
    await expect(job).toBeVisible();

    // The workers have to actually extract from Loki and write to storage.
    await expect(job.locator(".state")).toHaveText("READY", { timeout: 150_000 });
    await expect(job).toContainText("entries");
    await expect(job.getByRole("link", { name: "Download script" })).toBeVisible();
    await expect(job.getByRole("link", { name: "Download .zip" })).toBeVisible();
  });

  test("pods are listed from metrics, and a picked pod narrows the query", async ({ page }) => {
    await signIn(page, "alice");

    // Listed from kube-state-metrics for the chosen namespace and range: the
    // seeded API pods ran in platform-dev during the last hour.
    const pods = page.locator('[data-testid="pods"]');
    const api = pods.getByRole("checkbox", { name: /^platform-api-/ }).first();
    await expect(api).toBeVisible({ timeout: 30_000 });
    await expect(pods).not.toContainText("payments-");
    const name = await api.getAttribute("value");
    await api.check();
    await expect(page.locator('[data-testid="scope-summary"]')).toContainText("1 pod");

    await page.getByRole("button", { name: "Estimate first" }).click();
    await page.getByText("What will be queried").click();
    await expect(page.locator(".estimate code")).toContainText(`pod="${name}"`);
  });

  test("the activity and audit pages are for administrators", async ({ page }) => {
    // alice can export, but is not an administrator: no other views, and a
    // link to one lands on the export form.
    await signIn(page, "alice");
    await expect(page.getByRole("button", { name: "Activity" })).toHaveCount(0);
    await page.goto("/#audit");
    await expect(page.getByRole("heading", { name: "New export" })).toBeVisible();
    await expect(page.locator('[data-testid="audit"]')).toHaveCount(0);
  });

  test("the audit page lists downloads", async ({ page }) => {
    await signIn(page, "carol");

    await page.getByRole("button", { name: "Audit" }).click();
    const audit = page.locator('[data-testid="audit"]');
    // The shell suites, which run first, have downloaded exports.
    await expect(audit.locator('[data-testid="audit-row"]').first()).toBeVisible();
    await expect(audit.locator("thead")).toContainText("Who");
    await expect(page).toHaveURL(/#audit$/);

    await audit.getByLabel("Filter downloads").fill("no-such-person");
    await expect(audit.locator('[data-testid="audit-row"]')).toHaveCount(0);
  });

  test("the activity page shows how exporting has gone", async ({ page }) => {
    await signIn(page, "carol");

    await page.getByRole("button", { name: "Activity" }).click();
    const activity = page.locator('[data-testid="activity"]');
    await expect(activity.locator('[data-testid="tile"]')).toHaveCount(9);
    await expect(activity.locator('[data-testid="chart"]')).toHaveCount(3);
    await expect(activity.locator('[data-testid="failures"]')).toBeVisible();
    // A link to it opens it.
    await expect(page).toHaveURL(/#activity$/);
    await page.reload();
    await expect(activity).toBeVisible();

    await activity.getByRole("button", { name: "7 days" }).click();
    await expect(activity).toContainText("Last 7 days");
  });

  test("someone not signed in lands on a page that says what LogGate is", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/welcome$/);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Bulk log export");
    await expect(page.getByRole("link", { name: "Read the guide" })).toBeVisible();
  });

  test("the guide can be read without signing in", async ({ page }) => {
    await page.goto("/guide");
    await expect(page.getByRole("heading", { name: "LogGate user guide" })).toBeVisible();
    // Its screenshots load, and its contents link into it.
    const shot = page.locator('img[src="/guide/images/03-estimate.png"]');
    await expect(shot).toBeVisible();
    expect(await shot.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
    await page.getByRole("navigation", { name: "Sections" }).getByRole("link", { name: "Downloading" }).click();
    await expect(page).toHaveURL(/#downloading$/);
    // Nothing on it signed anyone in.
    expect((await page.request.get("/api/me", { maxRedirects: 0 })).status()).toBe(401);
  });

  test("signing out signs out of the identity provider too", async ({ page }) => {
    await signIn(page, "alice");

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "You're signed out" })).toBeVisible();

    // Back to the application, which must now ask who you are, not sign the
    // same person straight back in.
    await page.getByRole("link", { name: "Sign in again" }).click();
    await expect(page.locator("#username")).toBeVisible();
  });

  test("the output format is chosen per export", async ({ page }) => {
    await signIn(page, "alice");

    const output = page.getByRole("group", { name: "Output format" });
    await expect(output.getByRole("button", { name: "JSON lines" })).toHaveAttribute("aria-pressed", "true");
    await output.getByRole("button", { name: "Raw log lines" }).click();
    await expect(page.locator('[data-testid="scope-summary"]')).toContainText("raw lines");
  });

  test("the theme can be chosen rather than only inherited", async ({ page }) => {
    await signIn(page, "alice");

    const control = page.getByRole("button", { name: "System theme" });
    await expect(control).toBeVisible();
    await expect(page.locator("html")).not.toHaveAttribute("data-theme", /.*/);

    await control.click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
    await page.getByRole("button", { name: "Light" }).click();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

    // The choice belongs to the browser, so it survives a reload.
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await expect(page.getByRole("button", { name: "Dark" })).toBeVisible();
  });

  test("the allowance is visible before an export is started", async ({ page }) => {
    await signIn(page, "alice");

    const allowance = page.locator('[data-testid="allowance"]');
    await allowance.getByText("Your allowance").click();
    await expect(allowance).toContainText("platform");
    await expect(allowance).toContainText("exports running");
    await expect(allowance).toContainText("Finished exports are kept for");
  });

  test("an estimate carries the quota verdict with it", async ({ page }) => {
    await signIn(page, "alice");

    await page.getByRole("button", { name: "Estimate first" }).click();

    // Admitted, so the start button names what it is about to start rather
    // than warning about it.
    await expect(page.locator('[data-testid="refusal"]')).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Start export \(/ })).toBeVisible();
  });

  test("team-label mode shows the one cluster exports come from", async ({ page }) => {
    await signIn(page, "alice");

    // Fixed rather than offered: Kubernetes here can only vouch for its own
    // cluster's namespaces, so there is nothing to choose.
    const pinned = page.locator('[data-testid="pinned-cluster"]');
    await expect(pinned).toContainText("k3d-loggate");
    await expect(page.locator('[data-testid="clusters"]')).toHaveCount(0);

    await page.getByRole("button", { name: "Estimate first" }).click();
    await page.getByText("What will be queried").click();
    await expect(page.locator(".estimate code")).toContainText('cluster="k3d-loggate"');
  });

  test("dave has no namespaces and is told what to do about it", async ({ page }) => {
    await signIn(page, "dave");

    await expect(page.getByRole("heading", { name: "No namespaces" })).toBeVisible();
    await expect(page.locator(".empty")).toContainText("owns a labelled namespace");
  });
});
