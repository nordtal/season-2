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
  // VITE_OUT_DIR lets Gradle redirect the output when it moves this module's build root.
  build: {
    outDir: process.env.VITE_OUT_DIR ?? path.resolve(here, "../build/frontend-dist"),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    // A prefix missing here falls through to Vite's SPA fallback, which answers with index.html and not a 404.
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/auth": "http://127.0.0.1:8080",
    },
  },
  // The log window's buffer is a hook that only exists inside React.
  test: {
    environment: "jsdom",
    setupFiles: ["./src/vitest.setup.ts"],
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    // describe, it and expect are imported explicitly, so the type-check covers them.
    globals: false,
    // A ceiling so the worker pool alone cannot saturate a host that runs other services.
    maxWorkers: 4,
  },
})
