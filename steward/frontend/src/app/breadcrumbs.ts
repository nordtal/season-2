import { useRouterState } from "@tanstack/react-router"

import { t } from "@/lib/texts"

/**
 * The trail over the page, built here for every shell; `app/shell.tsx` re-exports {@link breadcrumbsFor} for its test.
 */
export type Crumb = { label: string; href: string }

/** The path segments that name a page of their own; any other segment is shown as it reads, a service name. */
const SECTIONS = new Set(["services", "operations", "updates", "backups", "season", "access", "payments", "journal"])

/** A path segment decoded for reading, or as it arrived, since a malformed escape would blank the page. */
function readable(segment: string) {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

/** One crumb per path segment, always starting at the overview. */
export function breadcrumbsFor(pathname: string): Crumb[] {
  const segments = pathname.split("/").filter(Boolean)
  const crumbs: Crumb[] = [{ label: t("steward.shell.page", { page: "overview" }), href: "/" }]
  let href = ""
  for (const segment of segments) {
    href += `/${segment}`
    crumbs.push({
      label: SECTIONS.has(segment) ? t("steward.shell.page", { page: segment }) : readable(segment),
      href,
    })
  }
  return crumbs
}

/** The trail of the page being shown. Needs a router; the island itself takes crumbs as a prop. */
export function useCrumbs(): Crumb[] {
  return useRouterState({ select: (state) => breadcrumbsFor(state.location.pathname) })
}
