import path from "node:path"
import { fileURLToPath } from "node:url"
import { defineConfig } from "vitest/config"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"

const here = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": path.resolve(here, "src") },
  },
  // VITE_OUT_DIR lets Gradle redirect the output when it moves this module's build root; Vite has no other way to learn of that move.
  build: {
    outDir: process.env.VITE_OUT_DIR ?? path.resolve(here, "../build/frontend-dist"),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    // A prefix missing here does not fail loudly: it falls through to Vite's SPA fallback, which answers with index.html instead of a 404.
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/auth": "http://127.0.0.1:8080",
    },
  },
  // jsdom is required because the log window's buffer is a hook that only exists inside React.
  test: {
    environment: "jsdom",
    // Raises Testing Library's async budget; the file says why.
    setupFiles: ["./src/vitest.setup.ts"],
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    // No globals: describe, it and expect are imported explicitly so the type-check covers them.
    globals: false,
    // A ceiling, not a tuning: small enough that the worker pool alone cannot saturate a host that also runs other services.
    maxWorkers: 4,
  },
})
