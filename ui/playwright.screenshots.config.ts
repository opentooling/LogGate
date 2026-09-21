import { defineConfig, devices } from "@playwright/test";

/**
 * Captures the screenshots in docs/images for the user guide.
 *
 * Kept apart from the end-to-end config so the test run never produces them:
 * these drive a deployed stack through a scripted tour and overwrite files in
 * the repository, which is a deliberate act (`npm run screenshots`), not a
 * side effect of testing.
 */
export default defineConfig({
  testDir: "./screenshots",
  timeout: 600_000,
  expect: { timeout: 60_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    ...devices["Desktop Chrome"],
    baseURL: process.env.APP_URL ?? "http://loggate.localtest.me:8088",
    viewport: { width: 1280, height: 800 },
    // Sharp on high-density screens, which is how most people will read them.
    deviceScaleFactor: 2,
  },
});
