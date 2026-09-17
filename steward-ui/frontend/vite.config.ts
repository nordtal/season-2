import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const here = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { "@": path.resolve(here, "src") },
  },
  // Gradle owns this directory: processResources copies it into build/resources/main/web, which is
  // where the Javalin process serves it from inside the jar. It deliberately sits outside the npm
  // project so that nothing generated ever lands next to the sources.
  //
  // `VITE_OUT_DIR` is how Gradle says where that is (season-2-ops/30). `-PbuildRoot` moves every
  // module's output tree somewhere else so two agents can build at once, and it moves this module's
  // `frontendDistDirectory` with it - but Vite is a separate process reading this file, it has
  // never heard of the property, and it used to keep writing into the one shared directory. Nothing
  // failed loudly: `processResources` copied faithfully out of the moved directory, which was
  // empty, and the first sign of it was Javalin refusing to start a test with "Static resource
  // directory with path: '/web' does not exist". The fallback is the value that stood here before,
  // so `npm run build` by hand, without Gradle, lands where it always did.
  build: {
    outDir: process.env.VITE_OUT_DIR ?? path.resolve(here, "../build/frontend-dist"),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    // `npm run dev` talks to a locally running StewardUi rather than to the container behind Caddy.
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
  // The tests run under jsdom because two of the four things worth testing here - the log window's
  // buffer and the traffic light - are a hook and a decision that only exist inside React. A pure Node
  // environment would leave exactly those untested, which is where the bugs were.
  test: {
    environment: "jsdom",
    // Raises Testing Library's async budget; the file says why.
    setupFiles: ["./src/vitest.setup.ts"],
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    // No globals. `describe`, `it` and `expect` are imported in every test file, so a reader can
    // see where they come from and the type-check covers them like any other import.
    globals: false,
  },
});
