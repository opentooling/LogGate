import { expect, test, type Page } from "@playwright/test";

const PASSWORD = process.env.DEMO_PASSWORD ?? "loggate";

/** Signs in through the real Keycloak login form. */
async function signIn(page: Page, username: string) {
  await page.goto("/");
  // Keycloak's own page, not ours.
  await page.fill("#username", username);
  await page.fill("#password", PASSWORD);
  await page.click("#kc-form-login button[type=submit], #kc-login");
  await expect(page.getByRole("heading", { name: "LogGate" })).toBeVisible();
}

test.describe("LogGate", () => {
  test("alice sees only her own namespace", async ({ page }) => {
    await signIn(page, "alice");

    const options = page.locator("select option");
    await expect(options).toHaveCount(1);
    await expect(options.first()).toContainText("platform-dev");
    // payments-dev belongs to another team and must not be offered at all.
    await expect(page.locator("select")).not.toContainText("payments-dev");
  });

  test("an estimate is shown before committing to an export", async ({ page }) => {
    await signIn(page, "alice");
    await page.selectOption("select", "platform-dev");

    await page.getByRole("button", { name: "Estimate first" }).click();

    const estimate = page.locator(".estimate");
    await expect(estimate).toBeVisible();
    await expect(estimate).toContainText('{namespace="platform-dev"}');
  });

  test("an export runs to completion and offers its files", async ({ page }) => {
    await signIn(page, "alice");
    await page.selectOption("select", "platform-dev");

    await page.getByRole("button", { name: "Start export" }).click();

    const job = page.locator('[data-testid="job"]').first();
    await expect(job).toBeVisible();

    // The workers have to actually extract from Loki and write to MinIO.
    await expect(job.locator(".state")).toHaveText("READY", { timeout: 150_000 });
    await expect(job).toContainText("entries");
    await expect(job.getByRole("link", { name: "Download script" })).toBeVisible();
    await expect(job.getByRole("link", { name: "Download .zip" })).toBeVisible();
  });

  test("dave has no namespaces and is told why", async ({ page }) => {
    await signIn(page, "dave");

    await expect(page.getByRole("heading", { name: "No namespaces" })).toBeVisible();
    await expect(page.locator(".empty")).toContainText("owns a labelled namespace");
  });
});
