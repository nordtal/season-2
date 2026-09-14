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
import { ConfigurationFilePage, ConfigurationPage } from "@/pages/configuration"
import { SeasonPage } from "@/pages/season"
import { JournalPage, AccountsPage, PaymentsPage, AccessPage } from "@/pages/access"
import { StatusPage } from "@/pages/status"

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
  createRoute({ getParentRoute: () => rootRoute, path: "/", component: StatusPage }),
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
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/configuration",
    component: ConfigurationPage,
  }),
  // A splat, not a `$file`. A config file is identified by its path under the mount, and a
  // Paper plugin's always has slashes in it (`smp/nordtal-smp/config.yml`); a single named
  // parameter stops at the first one and would 404 exactly those files and no others.
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/configuration/$",
    component: ConfigurationFilePage,
  }),
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
