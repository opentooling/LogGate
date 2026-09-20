import { expect, test, type Page } from "@playwright/test";

const PASSWORD = process.env.DEMO_PASSWORD ?? "loggate";

/** Signs in through the real Keycloak login form. */
async function signIn(page: Page, username: string) {
  await page.goto("/");
  await page.fill("#username", username);
  await page.fill("#password", PASSWORD);
  await page.click("#kc-form-login button[type=submit], #kc-login");
  await expect(page.getByRole("heading", { name: "LogGate" })).toBeVisible();
}

test.describe("LogGate", () => {
  test("alice is offered only her own namespace", async ({ page }) => {
    await signIn(page, "alice");

    const namespaces = page.locator(".check");
    await expect(namespaces).toHaveCount(1);
    await expect(namespaces.first()).toContainText("platform-dev");
    // payments-dev belongs to another team and must not appear at all.
    await expect(page.locator(".checks")).not.toContainText("payments-dev");
  });

  test("a time range preset sets the range and shows its duration", async ({ page }) => {
    await signIn(page, "alice");

    await page.getByRole("button", { name: "Last 6 hours" }).click();

    await expect(page.locator("legend", { hasText: "Time range" })).toContainText("6h");
  });

  test("the estimate is shown before committing, with the generated query", async ({ page }) => {
    await signIn(page, "alice");

    await page.getByRole("button", { name: "Estimate first" }).click();

    const estimate = page.locator(".estimate");
    await expect(estimate).toBeVisible();
    await expect(estimate.locator(".estimate-headline")).toContainText(/B|KB|MB|GB/);

    // The selector is generated, never typed, so it is worth showing.
    await page.getByText("What will be queried").click();
    await expect(estimate.locator("code")).toContainText('{namespace="platform-dev"}');
  });

  test("an export runs to completion and offers its files", async ({ page }) => {
    await signIn(page, "alice");

    // Narrowed to the small steady workload. Without this the range can cover
    // whatever bulk data happens to be in the cluster, and the test measures
    // the machine rather than the behaviour.
    await page.getByLabel("Pods").fill("platform-api-*");

    await page.getByRole("button", { name: "Start export" }).click();

    const job = page.locator('[data-testid="job"]').first();
    await expect(job).toBeVisible();

    // The workers have to actually extract from Loki and write to storage.
    await expect(job.locator(".state")).toHaveText("READY", { timeout: 150_000 });
    await expect(job).toContainText("entries");
    await expect(job.getByRole("link", { name: "Download script" })).toBeVisible();
    await expect(job.getByRole("link", { name: "Download .zip" })).toBeVisible();
  });

  test("dave has no namespaces and is told what to do about it", async ({ page }) => {
    await signIn(page, "dave");

    await expect(page.getByRole("heading", { name: "No namespaces" })).toBeVisible();
    await expect(page.locator(".empty")).toContainText("owns a labelled namespace");
  });
});
