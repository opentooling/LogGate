import { defineConfig, devices } from "@playwright/test";

/**
 * Runs against a deployed LogGate, not a dev server: the point is to exercise
 * the real OIDC round trip and the real workers, which a mocked backend would
 * not.
 */
export default defineConfig({
  testDir: "./e2e",
  // An export has to actually run, so these are not fast.
  timeout: 180_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? "list" : [["list"]],
  use: {
    baseURL: process.env.APP_URL ?? "http://loggate.localtest.me:8088",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
