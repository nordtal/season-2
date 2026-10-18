import { createRootRoute, createRoute, createRouter, lazyRouteComponent, redirect } from "@tanstack/react-router"

import { NotFoundPage } from "@/app/not-found"
import { Shell } from "@/app/shell"
import { UpdateRunPage } from "@/pages/operations"
import { UpdatesPage } from "@/pages/updates"
import { BackupsPage, BackupRunDetailPage } from "@/pages/backups"
import { ServicePage, serviceSearch } from "@/pages/service"
import { SeasonPage, seasonSearch } from "@/pages/season"
import { TranslationsPage, translationsSearch } from "@/pages/translations"
import { AccessPage } from "@/pages/access"
import { JournalPage } from "@/pages/journal"
import { PaymentsPage } from "@/pages/payments"
import { PersonPage } from "@/pages/person"
import { OverviewPage } from "@/pages/overview"
import { AlertsPage } from "@/pages/alerts"

/**
 * The route tree, written out, because a generated `routeTree.gen.ts` in src/ would keep `viteBuild` out of date.
 */

const rootRoute = createRootRoute({
  component: Shell,
  notFoundComponent: NotFoundPage,
})

const routes = [
  createRoute({ getParentRoute: () => rootRoute, path: "/", component: OverviewPage }),
  /**
   * The open tab and file live in the URL, so a reload stays on them and Ctrl-K can land on a tab.
   */
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/services/$name",
    component: ServicePage,
    validateSearch: serviceSearch,
  }),
  /** Operations is Updates and Backups. The two old addresses redirect and do not 404. */
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations",
    beforeLoad: () => {
      throw redirect({ to: "/operations/updates" })
    },
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/runs/$id",
    beforeLoad: ({ params }) => {
      throw redirect({ to: "/operations/updates/$id", params: { id: params.id } })
    },
  }),
  createRoute({ getParentRoute: () => rootRoute, path: "/operations/updates", component: UpdatesPage }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/updates/$id",
    component: UpdateRunPage,
  }),
  /** The list page, which the report page's "Backups" crumb links to. */
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/backups",
    component: BackupsPage,
  }),
  /** The id is a run's, and the page lists that run's archives. */
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/backups/$id",
    component: BackupRunDetailPage,
  }),
  /**
   * There is no `/configuration` or `/settings`. Files are cards on their service's page, and the account is the popover.
   */
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/season",
    component: SeasonPage,
    validateSearch: seasonSearch,
  }),
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/translations",
    component: TranslationsPage,
    validateSearch: translationsSearch,
  }),
  createRoute({ getParentRoute: () => rootRoute, path: "/access", component: AccessPage }),
  /** The id is the person's Discord id, which every surface already carries. */
  createRoute({ getParentRoute: () => rootRoute, path: "/access/$id", component: PersonPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/payments", component: PaymentsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/journal", component: JournalPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/alerts", component: AlertsPage }),
  // The run page's layouts on real runs, loaded apart. It goes once one is picked.
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/designs/run-page",
    component: lazyRouteComponent(() => import("@/app/designs/run-page"), "RunPageDesigns"),
  }),
]

const routeTree = rootRoute.addChildren(routes)

export const router = createRouter({
  routeTree,
  defaultPreload: "intent",
  // Served from a jar behind Caddy at the root of its own host, so there is no basepath.
  scrollRestoration: true,
})

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router
  }
}
