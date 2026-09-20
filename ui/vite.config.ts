import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    // Unit tests only. The Playwright specs under e2e/ drive a deployed stack
    // and are run by `npx playwright test`.
    include: ["src/**/*.test.ts"],
  },
  build: {
    // Built into the backend's static resources, so the app is one deployable
    // and the SPA is same-origin with the API it calls.
    outDir: "dist",
    emptyOutDir: true,
  },
  server: {
    // For `npm run dev` against a locally running backend.
    proxy: {
      "/api": "http://localhost:8080",
      "/oauth2": "http://localhost:8080",
      "/login": "http://localhost:8080",
      "/logout": "http://localhost:8080",
    },
  },
});
