import { useRouterState } from "@tanstack/react-router"

import { t } from "@/lib/texts"

/**
 * A breadcrumb of the page trail. `app/shell.tsx` re-exports {@link breadcrumbsFor} for its test.
 */
export type Crumb = { label: string; href: string }

/**
 * The path segments that name a page. Any other segment is a service name and is shown as it is.
 */
const SECTIONS = new Set(["services", "operations", "updates", "backups", "season", "access", "payments", "journal"])

/** Returns a path segment decoded, or as it arrived when the escape is malformed. */
function readable(segment: string) {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

/** Returns one crumb per path segment, starting at the overview. */
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

/** Returns the trail of the shown page. It needs a router. */
export function useCrumbs(): Crumb[] {
  return useRouterState({ select: (state) => breadcrumbsFor(state.location.pathname) })
}
