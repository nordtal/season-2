import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import type { ProxyOptions } from "vite"
import { defineConfig } from "vitest/config"
import react from "@vitejs/plugin-react"
import tailwindcss from "@tailwindcss/vite"

const here = path.dirname(fileURLToPath(import.meta.url))

// The Steward UI stack (steward/ui-stack) points the proxy at its preview and hands over the preview's sign-in.
const api = process.env.STEWARD_API ?? "http://127.0.0.1:8080"
const previewState = process.env.STEWARD_PREVIEW_STATE

/** The preview's session cookie, read on every request, since a restarted preview signs in anew. */
function previewCookie(): string | undefined {
  if (previewState === undefined) return undefined
  try {
    const state: unknown = JSON.parse(readFileSync(previewState, "utf8"))
    if (typeof state !== "object" || state === null || !("cookies" in state)) return undefined
    const { cookies } = state
    if (!Array.isArray(cookies)) return undefined
    return cookies
      .map((cookie: unknown) =>
        typeof cookie === "object" && cookie !== null && "name" in cookie && "value" in cookie
          ? `${String(cookie.name)}=${String(cookie.value)}`
          : "",
      )
      .filter((pair) => pair !== "")
      .join("; ")
  } catch {
    return undefined
  }
}

const proxied: ProxyOptions = {
  target: api,
  configure: (proxy) => {
    proxy.on("proxyReq", (request) => {
      const cookie = previewCookie()
      if (cookie !== undefined) request.setHeader("cookie", cookie)
    })
  },
}

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
      "/api": proxied,
      "/auth": proxied,
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
    // A page test that clicks through several fetches outlasts 5 seconds on a loaded CI runner.
    testTimeout: 15_000,
  },
})
