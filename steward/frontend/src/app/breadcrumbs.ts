import { useRouterState } from "@tanstack/react-router"

import { t } from "@/lib/texts"

export type Crumb = { label: string; href: string }

/** Any segment outside this set is a service name and is shown as it is. */
const SECTIONS = new Set(["services", "operations", "updates", "backups", "season", "access", "payments", "journal"])

function readable(segment: string) {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

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

export function useCrumbs(): Crumb[] {
  return useRouterState({ select: (state) => breadcrumbsFor(state.location.pathname) })
}
