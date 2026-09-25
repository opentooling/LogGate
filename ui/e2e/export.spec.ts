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
  await expect(page.getByRole("navigation", { name: "Views" })).toBeVisible();
}

/** Opens one of the query bar's chips. */
async function openChip(page: Page, name: string) {
  await page.getByTestId(`${name}-chip`).click();
  return page.getByTestId(`${name}-panel`);
}

/** The export's size, once it has been asked for. */
async function sized(page: Page) {
  const estimate = page.getByTestId("estimate");
  await expect(estimate).toContainText(/≈ .*(B|KB|MB|GB)/, { timeout: 30_000 });
  return estimate;
}

test.describe("LogGate", () => {
  test("alice is offered only her own namespace", async ({ page }) => {
    await signIn(page, "alice");

    // Chosen for her already, as a team usually wants its own.
    await expect(page.getByTestId("namespaces-chip")).toContainText("platform-dev");
    const panel = await openChip(page, "namespaces");
    const list = panel.getByTestId("namespaces");
    await expect(list.getByRole("checkbox")).toHaveCount(1);
    await expect(list).toContainText("platform-dev");
    // payments-dev belongs to another team and must not appear at all.
    await expect(list).not.toContainText("payments-dev");
  });

  test("a time range preset sets the range and says which", async ({ page }) => {
    await signIn(page, "alice");

    const when = page.getByTestId("when-chip");
    await expect(when).toContainText("Last hour");
    const panel = await openChip(page, "when");
    await expect(panel.getByRole("button", { name: "Last hour" })).toHaveAttribute("aria-pressed", "true");

    await panel.getByRole("button", { name: "Last 6 hours" }).click();
    await expect(when).toContainText("Last 6 hours");
    await expect(panel.getByRole("button", { name: "Last hour" })).toHaveAttribute("aria-pressed", "false");

    // A range edited by hand is no longer any preset, and the chip says what it is.
    await panel.getByLabel("From", { exact: true }).fill("2026-01-01T00:00");
    await expect(panel.locator('.when-presets [aria-pressed="true"]')).toHaveCount(0);
    await expect(when).toContainText("1 Jan");
  });

  test("the size is shown before anything runs, with the generated query", async ({ page }) => {
    await signIn(page, "alice");

    // Asked for as soon as the choice is complete, without a button.
    await sized(page);
    await page.getByText("Details").click();
    await expect(page.locator(".estimate-details code")).toContainText('namespace="platform-dev"');
  });

  test("an export runs to completion and offers its files", async ({ page }) => {
    await signIn(page, "alice");

    // Narrowed to the small steady workload. Without this the range can cover
    // whatever bulk data happens to be in the cluster, and the test measures
    // the machine rather than the behaviour.
    const pods = await openChip(page, "pods");
    await pods.getByRole("button", { name: "Match a pattern" }).click();
    await pods.getByLabel("Pod pattern").fill("platform-api-*");
    await page.keyboard.press("Escape");
    await sized(page);

    await page.getByRole("button", { name: "Start export" }).click();

    const exports = page.getByTestId("exports");
    await exports.getByRole("button", { name: /All/ }).click();
    const job = exports.locator('[data-testid="job"]').first();
    await expect(job).toBeVisible();

    // The workers have to actually extract from Loki and write to storage.
    await expect(job.locator(".state")).toHaveText("Ready", { timeout: 150_000 });
    await expect(job).toContainText("entries");
    await job.getByRole("button", { name: /Download/ }).click();
    await expect(job.getByRole("link", { name: "Download script" })).toBeVisible();
    await expect(job.getByRole("link", { name: "Download .zip" })).toBeVisible();
  });

  test("finished exports are found by tab and by search, and can be run again", async ({ page }) => {
    await signIn(page, "alice");

    const exports = page.getByTestId("exports");
    await exports.getByRole("button", { name: /Finished/ }).click();
    const rows = exports.locator('[data-testid="job"]');
    await expect(rows.first()).toBeVisible();
    // Nothing still moving is on this tab.
    await expect(rows.filter({ has: page.locator(".st-run") })).toHaveCount(0);

    await exports.getByLabel("Search exports").fill("no-such-namespace");
    await expect(rows).toHaveCount(0);
    await exports.getByLabel("Search exports").fill("platform-dev");
    await expect(rows.first()).toBeVisible();

    // Run again refills the query with that export's scope.
    const again = exports.getByRole("button", { name: "Run again" }).first();
    if (await again.count()) {
      await again.click();
      await expect(page.getByTestId("namespaces-chip")).toContainText("platform-dev");
    }
  });

  test("pods are listed from metrics, and a picked pod narrows the query", async ({ page }) => {
    await signIn(page, "alice");

    // Listed from kube-state-metrics for the chosen namespace and range: the
    // seeded API pods ran in platform-dev during the last hour.
    const panel = await openChip(page, "pods");
    const list = panel.getByTestId("pods");
    const api = list.getByRole("checkbox", { name: /^platform-api-/ }).first();
    await expect(api).toBeVisible({ timeout: 30_000 });
    await expect(list).not.toContainText("payments-");
    const name = await api.getAttribute("value");
    await api.check();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("pods-chip")).toContainText("1 chosen");

    await sized(page);
    await page.getByText("Details").click();
    await expect(page.locator(".estimate-details code")).toContainText(`pod="${name}"`);
  });

  test("the activity and audit pages are for administrators", async ({ page }) => {
    // alice can export, but is not an administrator: no other views, and a
    // link to one lands on the export view.
    await signIn(page, "alice");
    await expect(page.getByRole("button", { name: "Activity" })).toHaveCount(0);
    await page.goto("/#audit");
    await expect(page.getByTestId("query")).toBeVisible();
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
    await expect(page).toHaveURL(/\/welcome$/);
    await expect(page.getByRole("heading", { level: 1 })).toContainText("Bulk log export");

    // Back to the application, which must now ask who you are, not sign the
    // same person straight back in.
    await page.getByRole("link", { name: "Sign in with single sign-on" }).click();
    await expect(page.locator("#username")).toBeVisible();
  });

  test("the output format is chosen per export", async ({ page }) => {
    await signIn(page, "alice");

    const output = page.getByTestId("output-chip");
    await expect(output).toContainText("JSON");
    const panel = await openChip(page, "output");
    await panel.getByRole("button", { name: /Raw log lines/ }).click();
    await expect(output).toContainText("Raw lines");
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

  test("the allowance is one click from the start button", async ({ page }) => {
    await signIn(page, "alice");

    await expect(page.getByTestId("allowance-chip")).toContainText("running");
    const panel = await openChip(page, "allowance");
    await expect(panel).toContainText("Your allowance");
    await expect(panel).toContainText("platform");
    await expect(panel).toContainText("Finished exports are kept for");
  });

  test("an estimate carries the quota verdict with it", async ({ page }) => {
    await signIn(page, "alice");

    await sized(page);
    // Admitted, so Start is offered rather than a refusal.
    await expect(page.locator('[data-testid="refusal"]')).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Start export" })).toBeEnabled();
  });

  test("team-label mode shows the one cluster exports come from", async ({ page }) => {
    await signIn(page, "alice");

    // Fixed rather than offered: Kubernetes here can only vouch for its own
    // cluster's namespaces, so there is nothing to choose.
    await expect(page.getByTestId("pinned-cluster")).toContainText("k3d-loggate");
    await expect(page.getByTestId("clusters-chip")).toHaveCount(0);

    await sized(page);
    await page.getByText("Details").click();
    await expect(page.locator(".estimate-details code")).toContainText('cluster="k3d-loggate"');
  });

  test("dave has no namespaces and is told what to do about it", async ({ page }) => {
    await signIn(page, "dave");

    await expect(page.getByRole("heading", { name: "No namespaces" })).toBeVisible();
    await expect(page.locator(".empty")).toContainText("owns a labelled namespace");
  });
});
