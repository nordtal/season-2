import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
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
  build: {
    outDir: path.resolve(here, "../build/frontend-dist"),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    // `npm run dev` talks to a locally running StewardUi rather than to the container behind Caddy.
    proxy: { "/api": "http://127.0.0.1:8080" },
  },
});
