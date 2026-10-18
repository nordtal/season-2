# Nordtal Steward: frontend

The web interface of the season 2 stack. It uses React, TypeScript, Vite, TanStack Router and Query,
Tailwind v4 and shadcn/ui. Every page talks to the Javalin process in `steward/src/main/java`.

## Running it

The run configuration **dev ui** (or `./gradlew -q :dev:run --args="ui"`) starts the stack and runs
Vite on http://localhost:5173. [`deploy/README.md`](../../deploy/README.md#locally) has the rest. Put the
private Node from `steward/build/nodejs/` on `PATH` for these commands, or use `./gradlew :steward:viteTest`:

```sh
npm run typecheck    # tsc -b --noEmit, as `npm run build` runs first
npm run test         # vitest
```

A path outside `/api` and `/auth` needs a proxy line in `vite.config.ts` and a route in `Web`.
Without the proxy line, Vite answers the path with `index.html`. Add components with `npx shadcn@latest add`.

Vite writes to `steward/build/frontend-dist/`, and `processResources` copies that into the jar under `web/`.
Nothing generated is committed.

## Design rules

- Dark only, with no toggle.
- The one blue is the brand's, `Tone.BRAND`, which `generateApiTypes` writes into `src/brand.gen.css`. In
  Steward's own chrome it marks action and nothing else. No surface or chart series carries it. A
  preview of the game or Discord paints it where they do.
- Status has three colours: success, warning and `--destructive`.
- Rows are 34px, and a control inside one keeps its 36px target. On a touch screen both are 40px. A control
  drawn smaller, such as a link in a line of text, carries `tap-target`, which grows its hit area alone.
- Changing numbers are tabular.
- No `transition: all`, and no `outline: none` without a visible replacement.
- Every token lives in `src/index.css`. The brand blue lives in the generated `src/brand.gen.css`.

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

## Data

Every read is a hook in `lib/queries.ts`, and nothing polls. A hook that follows something that
changes spreads `live(...topics)` from `lib/live.ts` into its options. The one stream the shell holds
(`/api/live`, described in Steward's README) refetches the hook when one of its topics changes, and the
minute's reconciliation reads it anyway. A wait for the bot or a server ends at the next `REQUESTS`
change and never at a timer. `nothing-polls.test.ts` holds both rules. `lib/event-stream.ts` follows
every SSE route and owns the backoff and the reconnect.

The types of what the routes answer come from `lib/api.gen.ts`, which Steward's records write (see
Steward's README). `lib/api.ts` re-exports them and is otherwise the client. Never edit the generated
file by hand.

`AskThenAct` is every confirmation in front of a change. A change that returns a promise keeps the
dialog open until it settles and shows a refusal inside it.
