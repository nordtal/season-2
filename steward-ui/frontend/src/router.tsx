import { createRootRoute, createRoute, createRouter } from "@tanstack/react-router"

import { Shell } from "@/app/shell"
import {
  BetriebLaufPage,
  BetriebPage,
  BetriebPlanPage,
  BetriebSicherungPage,
  BetriebWiederherstellenPage,
  DienstPage,
  EinstellungenPage,
  JournalPage,
  KonfigurationPage,
  KontenPage,
  NotFoundPage,
  SaisonPage,
  ZahlungenPage,
  ZugaengePage,
  ZustandPage,
} from "@/pages/pages"

/**
 * The route tree, written out rather than generated.
 *
 * TanStack's file-based plugin would write `routeTree.gen.ts` into src/ on every build - which is
 * a generated file inside the directory Gradle declares as an input to `viteBuild`, i.e. a build
 * that is never up to date and a file somebody has to remember to commit. Fourteen routes do not
 * need a code generator, so this is the tree.
 */

const rootRoute = createRootRoute({
  component: Shell,
  notFoundComponent: NotFoundPage,
})

const routes = [
  createRoute({ getParentRoute: () => rootRoute, path: "/", component: ZustandPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/dienste/$name", component: DienstPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/betrieb", component: BetriebPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/betrieb/plan", component: BetriebPlanPage }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/betrieb/lauf/$id",
    component: BetriebLaufPage,
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/betrieb/sicherung/$id",
    component: BetriebSicherungPage,
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/betrieb/wiederherstellen",
    component: BetriebWiederherstellenPage,
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/konfiguration/$datei",
    component: KonfigurationPage,
  }),
  createRoute({ getParentRoute: () => rootRoute, path: "/saison", component: SaisonPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/zugaenge", component: ZugaengePage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/zahlungen", component: ZahlungenPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/konten", component: KontenPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/journal", component: JournalPage }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/einstellungen",
    component: EinstellungenPage,
  }),
]

const routeTree = rootRoute.addChildren(routes)

export const router = createRouter({
  routeTree,
  defaultPreload: "intent",
  // Served from a jar behind Caddy at the root of its own host, so no basepath.
  scrollRestoration: true,
})

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router
  }
}
