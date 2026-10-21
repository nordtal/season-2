import { useRouterState } from "@tanstack/react-router"

import type { Person } from "@/lib/api"
import { usePeople } from "@/lib/queries"
import { t } from "@/lib/texts"
import { personName } from "@/pages/access-person"

export type Crumb = { label: string; href: string }

/** Any segment outside this set is a service name and is shown as it is. */
const SECTIONS = new Set([
  "services",
  "operations",
  "updates",
  "backups",
  "season",
  "translations",
  "access",
  "payments",
  "journal",
  "alerts",
])

function readable(segment: string) {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

/** The Discord id a user's page carries in its path. */
function personId(pathname: string): string | undefined {
  return /^\/access\/([^/]+)$/.exec(pathname)?.[1]
}

/** A user's page is named after the user once `people` holds them, else by the id in its path. */
export function breadcrumbsFor(pathname: string, people?: Person[]): Crumb[] {
  const segments = pathname.split("/").filter(Boolean)
  const crumbs: Crumb[] = [{ label: t("steward.shell.page", { page: "overview" }), href: "/" }]
  const person = people?.find((candidate) => candidate.discordId === personId(pathname))
  let href = ""
  for (const segment of segments) {
    href += `/${segment}`
    crumbs.push({
      label: SECTIONS.has(segment) ? t("steward.shell.page", { page: segment }) : readable(segment),
      href,
    })
  }
  if (person) crumbs[crumbs.length - 1].label = personName(person)
  return crumbs
}

export function useCrumbs(): Crumb[] {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const people = usePeople(personId(pathname) !== undefined)
  return breadcrumbsFor(pathname, people.data)
}
