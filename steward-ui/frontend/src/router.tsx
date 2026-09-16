import { createRootRoute, createRoute, createRouter } from "@tanstack/react-router"

import { NotFoundPage } from "@/app/not-found"
import { Shell } from "@/app/shell"
import {
  OperationsRunPage,
  OperationsPage,
  OperationsPlanPage,
  OperationsBackupPage,
  OperationsRestorePage,
} from "@/pages/operations"
import { ServicePage } from "@/pages/service"
import { SettingsPage } from "@/pages/settings"
import { SeasonPage } from "@/pages/season"
import { JournalPage, AccountsPage, PaymentsPage, AccessPage } from "@/pages/access"
import { OverviewPage } from "@/pages/overview"

/**
 * The route tree, written out rather than generated.
 *
 * TanStack's file-based plugin would write `routeTree.gen.ts` into src/ on every build - which is
 * a generated file inside the directory Gradle declares as an input to `viteBuild`, i.e. a build
 * that is never up to date and a file somebody has to remember to commit. Fifteen routes do not
 * need a code generator, so this is the tree.
 */

const rootRoute = createRootRoute({
  component: Shell,
  notFoundComponent: NotFoundPage,
})

const routes = [
  createRoute({ getParentRoute: () => rootRoute, path: "/", component: OverviewPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/services/$name", component: ServicePage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/operations", component: OperationsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/operations/plan", component: OperationsPlanPage }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/runs/$id",
    component: OperationsRunPage,
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/backups/$id",
    component: OperationsBackupPage,
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/restore",
    component: OperationsRestorePage,
  }),
  // There is no `/configuration` route any more (2026-09-14). Every file belongs to exactly one
  // service, so it is a card on that service's page - see `components/steward/configuration.tsx`.
  createRoute({ getParentRoute: () => rootRoute, path: "/season", component: SeasonPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/access", component: AccessPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/payments", component: PaymentsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/accounts", component: AccountsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/journal", component: JournalPage }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/settings",
    component: SettingsPage,
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
