import { createRootRoute, createRoute, createRouter, redirect } from "@tanstack/react-router"

import { NotFoundPage } from "@/app/not-found"
import { Shell } from "@/app/shell"
import { UpdateRunPage } from "@/pages/operations"
import { UpdatesPage } from "@/pages/updates"
import { BackupsPage, BackupRunDetailPage } from "@/pages/backups"
import { ServicePage, serviceSearch } from "@/pages/service"
import { SeasonPage } from "@/pages/season"
import { AnnouncementsPage } from "@/pages/announcements"
import { JournalPage, PaymentsPage, AccessPage } from "@/pages/access"
import { OverviewPage } from "@/pages/overview"
import { ChartsGalleryPage } from "@/app/designs/charts-gallery"
import { TranslationsPage, translationsSearch } from "@/app/designs/translations/translations-page"

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
  // steward/140: the open tab and the chosen file are in the URL, so a reload stays where it was
  // and Ctrl-K can land on a tab. Console is the default and is never written out.
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/services/$name",
    component: ServicePage,
    validateSearch: serviceSearch,
  }),
  // Operations is two pages, Updates and Backups. The two old addresses a link or a push
  // notification may still carry are sent on rather than answered with a 404.
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
  // The list page steward/95 added, and the parent the report page's own breadcrumb had been
  // pointing at since there were breadcrumbs: `/operations/backups/<name>` draws a "Backups" crumb
  // whose href is this path, and until now that was a 404.
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/backups",
    component: BackupsPage,
  }),
  // The id is a run id: a run writes several archives, and "a backup" on the Backups page means
  // the run. The page lists that run's own archives, each one downloadable.
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/operations/backups/$id",
    component: BackupRunDetailPage,
  }),
  // There is no `/configuration` route any more (2026-09-14). Every file belongs to exactly one
  // service, so it is a card on that service's page - see `components/steward/configuration.tsx`.
  // Nor a `/settings` (2026-09-24): its account half is the popover behind the round picture, and
  // the thresholds it showed are set in the notifications dialog beside it.
  createRoute({ getParentRoute: () => rootRoute, path: "/season", component: SeasonPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/announcements", component: AnnouncementsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/access", component: AccessPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/payments", component: PaymentsPage }),
  createRoute({ getParentRoute: () => rootRoute, path: "/journal", component: JournalPage }),
  // The chart proposals for a service page's head, side by side. Goes with `app/designs/` once one is picked.
  createRoute({ getParentRoute: () => rootRoute, path: "/designs/charts", component: ChartsGalleryPage }),
  // The three translation editors on real bundles. Goes with `app/designs/` once one is picked.
  createRoute({
    getParentRoute: () => rootRoute,
    path: "/designs/translations",
    component: TranslationsPage,
    validateSearch: translationsSearch,
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
