import { useRouterState } from "@tanstack/react-router"

/**
 * The trail over the page, and the one place it is built.
 *
 * It lived in `app/shell.tsx` while there was a header to carry it. steward/89 takes the header
 * away and gives the trail to the island instead, and three shells now draw it - so it moved here,
 * where none of them owns it. `app/shell.tsx` re-exports {@link breadcrumbsFor} for
 * `shell.breadcrumbs.test.ts`, which is where the interesting half is tested.
 */
export type Crumb = { label: string; href: string }

const SECTION_LABELS: Record<string, string> = {
  services: "Services",
  operations: "Operations",
  plan: "Plan",
  runs: "Run",
  backups: "Backups",
  restore: "Restore",
  season: "Season",
  access: "Access",
  payments: "Payments",
  accounts: "Accounts",
  journal: "Journal",
}

/**
 * A path segment as a person should read it, or exactly as it arrived.
 *
 * `decodeURIComponent` throws on a malformed escape - `/services/%` is enough - and it is called
 * while the island renders, so the whole page became a blank screen for a URL somebody mistyped
 * or a link that lost a character. An undecodable segment is shown as it is; it is a breadcrumb,
 * not a value anything is computed from.
 */
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
  const crumbs: Crumb[] = [{ label: "Overview", href: "/" }]
  let href = ""
  for (const segment of segments) {
    href += `/${segment}`
    crumbs.push({
      label: SECTION_LABELS[segment] ?? readable(segment),
      href,
    })
  }
  return crumbs
}

/** The trail of the page being shown. Needs a router; the island itself takes crumbs as a prop. */
export function useCrumbs(): Crumb[] {
  return useRouterState({ select: (state) => breadcrumbsFor(state.location.pathname) })
}
