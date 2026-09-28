import { expect, test, type Page } from "@playwright/test";

/**
 * A scripted tour of LogGate that captures the user guide's screenshots.
 *
 * It runs as carol, who belongs to both demo teams, so every screen that has
 * something to say about more than one namespace says it. Each shot waits for
 * the state it is meant to show and fails if that state never arrives, rather
 * than capturing whatever happens to be on screen.
 *
 *   cd ui && npm run screenshots
 *
 * Needs the local stack deployed and seeded (see the README).
 */

const PASSWORD = process.env.DEMO_PASSWORD ?? "loggate";
const OUT = "../docs/images";

test.describe.configure({ mode: "serial" });

/**
 * Refuses to run while the cluster's clock and this machine's disagree.
 *
 * The tour asks for "the last 30 minutes" by this machine's clock, and Loki
 * stamps logs by the cluster's. Hours apart, the range lands where there is no
 * data, and every screenshot shows an empty export and nonsense durations
 * while the tour itself passes. A local VM that slept with the laptop is the
 * usual cause.
 */
test.beforeAll(async ({ request }) => {
  const response = await request.get("/readyz");
  const server = Date.parse(response.headers()["date"] ?? "");
  const skewSeconds = Math.round(Math.abs(server - Date.now()) / 1000);
  if (Number.isNaN(server) || skewSeconds > 60) {
    throw new Error(
      `The cluster's clock is ${skewSeconds}s away from this machine's. ` +
        "Resynchronise the VM running the cluster before taking screenshots.",
    );
  }
});

async function signIn(page: Page, username: string) {
  // Through the landing page, as a person arrives.
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in with single sign-on" }).click();
  await page.fill("#username", username);
  await page.fill("#password", PASSWORD);
  await page.click("#kc-form-login button[type=submit], #kc-login");
  await expect(page.getByRole("navigation", { name: "Views" })).toBeVisible();
}

/** A value for a datetime-local input, in the browser's own time zone. */
function localInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** Opens one of the query bar's chips, and returns its panel. */
async function openChip(page: Page, name: string) {
  await page.getByTestId(`${name}-chip`).click();
  return page.getByTestId(`${name}-panel`);
}

async function choose(page: Page, namespaces: string[]) {
  const panel = await openChip(page, "namespaces");
  for (const name of ["platform-dev", "payments-dev"]) {
    const box = panel.getByTestId("namespaces").getByRole("checkbox", { name: new RegExp(name) });
    if (namespaces.includes(name)) await box.check();
    else await box.uncheck();
  }
  await page.keyboard.press("Escape");
}

async function rangeFromNow(page: Page, minutesAgo: number) {
  const now = new Date();
  const panel = await openChip(page, "when");
  await panel.getByLabel("From", { exact: true }).fill(localInput(new Date(now.getTime() - minutesAgo * 60_000)));
  await panel.getByLabel("To", { exact: true }).fill(localInput(now));
  return panel;
}

/** Narrows by pod pattern rather than by picking from the list. */
async function podPattern(page: Page, pattern: string) {
  const panel = await openChip(page, "pods");
  await panel.getByRole("button", { name: "Match a pattern" }).click();
  await panel.getByLabel("Pod pattern").fill(pattern);
  await page.keyboard.press("Escape");
}

async function sized(page: Page) {
  await page.getByTestId("estimate-button").click();
  await expect(page.getByTestId("estimate")).toContainText(/≈/, { timeout: 30_000 });
}

const query = (page: Page) => page.getByTestId("query");
const exportsPanel = (page: Page) => page.getByTestId("exports");

test("signing in", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in with single sign-on" }).click();
  await expect(page.locator("#username")).toBeVisible();
  await page.screenshot({ path: `${OUT}/01-sign-in.png` });
});

test("the tour, as someone in two teams", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page, "carol");

  // A small export first, so there is a finished one to show. Thirty minutes
  // of one team's API pods finishes in about a minute.
  await choose(page, ["platform-dev"]);
  await rangeFromNow(page, 30);
  await page.keyboard.press("Escape");
  await podPattern(page, "platform-api-*");
  await sized(page);
  await page.getByRole("button", { name: "Start export" }).click();

  await exportsPanel(page).getByRole("button", { name: /All/ }).click();
  const ready = exportsPanel(page).locator('[data-testid="job"][data-state="READY"]').first();
  await expect(ready).toBeVisible({ timeout: 240_000 });
  await ready.getByRole("button", { name: /Download/ }).click();
  await ready.getByRole("button", { name: "The files, individually" }).click();
  await expect(exportsPanel(page).locator(".files li").first()).toBeVisible();
  await exportsPanel(page).screenshot({ path: `${OUT}/07-ready.png` });

  // Back to every pod, across both teams, over a day.
  const pods = await openChip(page, "pods");
  await pods.getByLabel("Pod pattern").fill("");
  await pods.getByRole("button", { name: "Pick from list" }).click();
  await page.keyboard.press("Escape");
  await choose(page, ["platform-dev", "payments-dev"]);
  // The pods that ran in both namespaces, grouped by namespace.
  const podList = await openChip(page, "pods");
  await expect(podList.getByRole("group", { name: /-dev$/ })).toHaveCount(2, { timeout: 30_000 });
  await podList.screenshot({ path: `${OUT}/12-pods.png` });
  await page.keyboard.press("Escape");
  const when = await openChip(page, "when");
  await when.getByRole("button", { name: "Last 24 hours" }).click();
  await page.keyboard.press("Escape");

  // The size, and what will be queried.
  await sized(page);
  await query(page).getByText("Details").click();
  await expect(query(page).locator(".breakdown li")).toHaveCount(2);
  await query(page).screenshot({ path: `${OUT}/03-estimate.png` });

  // Start it, and wait until there is enough progress for an estimate of the
  // time left, which is what makes a running export worth looking at.
  await page.getByRole("button", { name: "Start export" }).click();
  await exportsPanel(page).getByRole("button", { name: /Active/ }).click();
  const running = exportsPanel(page).locator('[data-testid="job"]').first();
  await expect(running.locator(".progress-detail")).toContainText("left", { timeout: 300_000 });
  await exportsPanel(page).screenshot({ path: `${OUT}/06-running.png` });

  // The whole page mid-export, in both themes: this is the one the README
  // shows, matched to the reader's own GitHub theme.
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: `${OUT}/00-overview-light.png` });
  await page.emulateMedia({ colorScheme: "dark" });
  await page.screenshot({ path: `${OUT}/00-overview-dark.png` });
  await page.emulateMedia({ colorScheme: "light" });

  // Put it away again; the tour only needed it running.
  await running.getByRole("button", { name: "Cancel" }).click();
  await exportsPanel(page).getByRole("button", { name: /All/ }).click();
  await expect(exportsPanel(page).locator('[data-testid="job"][data-state="CANCELLED"]').first()).toBeVisible({
    timeout: 180_000,
  });

  // The allowance, open.
  const allowance = await openChip(page, "allowance");
  await expect(allowance).toContainText("exports running");
  await allowance.screenshot({ path: `${OUT}/04-allowance.png` });
  await page.keyboard.press("Escape");

  // The activity page, with the exports this tour just made on it.
  await page.getByRole("button", { name: "Activity" }).click();
  const activity = page.locator('[data-testid="activity"]');
  await expect(activity.locator('[data-testid="chart"]')).toHaveCount(3);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: `${OUT}/13-activity.png`, fullPage: true });

  // The audit trail, with the downloads this tour just made on it.
  await page.getByRole("button", { name: "Audit" }).click();
  const audit = page.locator('[data-testid="audit"]');
  await expect(audit.locator('[data-testid="audit-row"]').first()).toBeVisible();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: `${OUT}/14-audit.png`, fullPage: true });
  await page.getByRole("button", { name: "Export" }).click();

  // A range longer than one export may cover is stopped as it is chosen.
  const range = await rangeFromNow(page, 3 * 24 * 60);
  // Typing leaves a focus ring and a selected field, which is not what anyone
  // sees when they read the message.
  await range.getByLabel("To", { exact: true }).blur();
  await expect(range.locator(".field-error")).toContainText("at most");
  await range.screenshot({ path: `${OUT}/05-range-limit.png` });
});

test("the public pages", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await page.goto("/welcome");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await page.screenshot({ path: `${OUT}/15-welcome.png` });
  await page.goto("/guide");
  await expect(page.getByRole("heading", { name: "LogGate user guide" })).toBeVisible();
  await page.screenshot({ path: `${OUT}/16-guide.png` });
});

test("someone with no team", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "light" });
  await signIn(page, "dave");
  await expect(page.getByRole("heading", { name: "No namespaces" })).toBeVisible();
  // Cropped to the content: the rest of the page is empty by design.
  await page.screenshot({
    path: `${OUT}/08-no-namespaces.png`,
    clip: { x: 0, y: 0, width: 1280, height: 400 },
  });
});

test("on a phone", async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
    colorScheme: "dark",
  });
  const page = await context.newPage();
  await signIn(page, "carol");
  await sized(page);
  await page.screenshot({ path: `${OUT}/09-phone.png` });
  await context.close();
});

/**
 * Open access mode, against a release running in it. Set OPEN_APP_URL to one;
 * deploy/local/openshift-check.sh with KEEP=1 leaves one at
 * http://ocp-loggate.localtest.me:8088. Skipped without it.
 */
test.describe("open access", () => {
  test.skip(!process.env.OPEN_APP_URL, "OPEN_APP_URL is not set");
  test.use({ baseURL: process.env.OPEN_APP_URL });

  test("every cluster Loki holds, to holders of the role", async ({ page }) => {
    await page.emulateMedia({ colorScheme: "light" });
    await signIn(page, "carol");

    // Choosing a cluster narrows the namespaces to that cluster's own, which
    // here is one LogGate has no Kubernetes access to at all.
    const clusters = await openChip(page, "clusters");
    await clusters.getByRole("checkbox", { name: "edge-eu" }).check();
    await page.keyboard.press("Escape");
    const namespaces = await openChip(page, "namespaces");
    await expect(namespaces).toContainText("checkout-prod");
    await expect(namespaces).not.toContainText("observability");
    await page.waitForTimeout(1500);
    // The query bar with the namespaces of the unreachable cluster open over it.
    await page.screenshot({ path: `${OUT}/10-open-access.png`, clip: { x: 0, y: 0, width: 1280, height: 480 } });
  });

  test("and nothing to anyone without it", async ({ page }) => {
    await page.emulateMedia({ colorScheme: "light" });
    await signIn(page, "bob");
    await expect(page.locator('[data-testid="barrier"]')).toContainText("export-logs");
    await page.screenshot({
      path: `${OUT}/11-no-role.png`,
      clip: { x: 0, y: 0, width: 1280, height: 400 },
    });
  });
});
