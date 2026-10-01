# Nordtal Steward: frontend

The web interface of the season 2 stack: React, TypeScript and Vite, with TanStack Router and Query,
Tailwind v4 and shadcn/ui. Every page talks to the Javalin process in `steward/src/main/java`.

## Running it

The run configuration **dev ui** (or `./gradlew -q :dev:run --args="ui"`) starts the stack and runs
Vite on http://localhost:5173; see [`deploy/README.md`](../../deploy/README.md#the-interface). With
the private Node from `steward/build/nodejs/` on `PATH`, or through `./gradlew :steward:viteTest`:

```sh
npm run typecheck    # tsc -b --noEmit, as `npm run build` runs first
npm run test         # vitest
```

A path outside `/api` and `/auth` needs a proxy line in `vite.config.ts` as well as a route in
`Web`, or Vite answers it with `index.html`. Add components with `npx shadcn@latest add`.

Vite writes to `steward/build/frontend-dist/`, which `processResources` copies into the jar
under `web/`. Nothing generated is committed.

## Design rules

- Dark only, with no toggle.
- Blue `#4a63d8` marks action and nothing else; no surface and no chart series carries it.
- Status has three colours: success, warning and `--destructive`.
- Rows are 34px; a control inside one keeps its 36px target.
- Changing numbers are tabular.
- No `transition: all`, and no `outline: none` without a visible replacement.
- Every token lives in `src/index.css`.

## Layout

```
src/
  app/            the shell: sidebar, command palette, the navigation registry
  components/ui/  shadcn/ui, edited only where the rules above require it
  pages/          one file per route
  router.tsx      the route tree, written out
  index.css       the theme
```

`src/app/navigation.ts` lists every place in the interface; a route missing from it cannot be reached.
