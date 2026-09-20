# Nordtal Steward - frontend

The web interface of the season 2 stack. React + TypeScript + Vite, TanStack Router and Query,
Tailwind v4 and shadcn/ui. This is the **alpha scaffold**: shell, theme, navigation and one
placeholder page per route. Nothing fetches anything yet.

## Running it

**There is no Node on the dev host and there is not going to be one.** The Gradle node plugin
downloads its own copy under `steward-ui/build/nodejs/`, and that is the only Node this repository
knows about.

```sh
# From the repository root. Downloads node, runs `npm ci`, runs `vite build`, and packs the result
# into the jar as web/.
sh gradlew :steward-ui:build
```

To work on the frontend with hot reload, put that private Node on `PATH` first:

```sh
export PATH="$PWD/steward-ui/build/nodejs/node-v24.21.0-linux-x64/bin:$PATH"
cd steward-ui/frontend
npm ci
npm run dev          # http://localhost:5173, /api and /auth proxied to a StewardUi on :8080
npm run typecheck    # tsc -b --noEmit, the same check `npm run build` runs first
```

**A path outside `/api` and `/auth` needs a line in `vite.config.ts` as well as a route in
`StewardUi`.** A prefix that is not proxied does not 404 - it falls into Vite's SPA fallback, which
answers `index.html` with a 200, so the dev server looks healthy and every call under that prefix
gets HTML where it wanted JSON. `/auth` was in that state until season-2-ops/144, which meant no
page in the dev server worked at all: the sign-in and all four WebAuthn steps live there, and
`Gate` wants a held key for every read. `src/dev-server-proxies-every-prefix.test.ts` is the guard.

`npx shadcn@latest add <component>` works here - `components.json` is wired and the registry is
reachable. Do not hand-roll a component the registry has.

## Where the build output goes

Vite writes to `steward-ui/build/frontend-dist/`, **not** into `src/main/resources/`. Gradle's
`processResources` copies that directory in under `web/`, so it reaches
`build/resources/main/web/` and from there the jar, which is what the Javalin process serves.
Nothing generated is ever committed.

## The colour discipline

Read this before adding a colour.

- **Dark only.** `index.html` carries `class="dark"` and nothing removes it. There is no toggle,
  no light palette and no `prefers-color-scheme` query. Adding one is a design change, not a
  feature.
- **Blue is action, and nothing else.** The brand is `#4a63d8` (`NetworkSpec.NORDTAL_BLUE`,
  measured off the logo). It appears on primary buttons, on the focus ring, on links and as the
  rule beside the active navigation item. **No surface carries it** - every page, card, popover,
  input and the sidebar itself is neutral near-black. That is the whole point: if no panel is
  blue, every blue thing on screen means something. The single exception is the logo mark, which
  is identity rather than a surface, and is not clickable-looking anywhere it appears.
- **Surfaces** are neutral: page `#0d0d0d`, cards `#141414`, popovers `#161616`, sidebar `#0a0a0a`.
  Foreground is a warm off-white `#fffcf6`, never pure white.
- **Status is three colours and no more**, and they are not Tailwind's defaults: success
  `#3fb98a`, warning `#d9a13b`, danger `#d8534a` (the token is `--destructive`, which is the name
  shadcn's components look for). Charts use these plus two neutrals and deliberately never the
  brand blue - a blue series would be the one blue on screen you cannot click.
- **Density.** Table rows are 34px with 12px of horizontal cell padding (`--row-height`,
  `--cell-padding`). A control that lands inside a row keeps its own 36px minimum
  (`--control-min-height`): rows are tight, hit areas are not. The compact button sizes stay
  compact to look at and grow an invisible 36px target around themselves.
- **Numbers that change are tabular.** `font-variant-numeric: tabular-nums` is applied to tables,
  inputs, code and anything marked `.tnum`, so a column does not dance while it updates.
- **Radius** is `0.375rem`, nested concentrically: an inner radius is the outer radius minus the
  padding between them.

## The quality bar these files are held to

- No `transition: all` anywhere - the properties are named. The registry ships `transition-all` in
  five components; all five were changed when they were added, and they will come back on the next
  `shadcn add`, so check.
- No `outline: none` without a visible replacement. Components pair it with
  `focus-visible:ring-[3px]`, and `:focus-visible` in `index.css` is the net under everything else.
- Every interactive element has hover, focus-visible, active and disabled states.
- The sidebar is `collapsible="none"` - always expanded, no icon rail, no collapse button. The
  labels are the point.

## Layout

```
src/
  app/          the shell: sidebar, header, command palette, the one navigation registry
  components/ui shadcn/ui, fetched with its CLI and edited only where the bar above required it
  hooks/        shadcn's use-mobile
  pages/        one placeholder per route, all in one file until they have content
  router.tsx    the route tree, written out rather than generated
  index.css     the theme - every token above lives here and nowhere else
```

`src/app/navigation.ts` is the single list of places in the interface. The sidebar renders it, the
command palette (⌘K / Strg+K) renders it, and the breadcrumb resolves titles from it. A route that
exists and is not in that list is a route nobody can reach.
