import { useRouterState } from "@tanstack/react-router"
import { useEffect } from "react"

import type { Person } from "@/lib/api"
import { usePeople } from "@/lib/queries"
import { t } from "@/lib/texts"
import { serviceTitle } from "@/lib/words"
import { personName } from "@/pages/access-person"

export type Crumb = { label: string; href: string }

/** Any segment outside this set is a service name, shown as the service is called below `services`. */
const SECTIONS = new Set([
  "services",
  "operations",
  "updates",
  "backups",
  "runs",
  "season",
  "translations",
  "access",
  "payments",
  "journal",
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

/** How the page below a section that lists runs names the run in its path. */
function runLabel(section: string | undefined, segment: string): string | undefined {
  if (section === "updates" || section === "runs") return t("steward.operations.run", { id: segment })
  if (section === "backups") return t("steward.backups.backup", { id: segment })
  return undefined
}

/**
 * A user's page is named after the user once `people` holds them, by the id in its path until then, and as nobody when
 * the loaded `people` lack the id.
 */
export function breadcrumbsFor(pathname: string, people?: Person[]): Crumb[] {
  const segments = pathname.split("/").filter(Boolean)
  const crumbs: Crumb[] = [{ label: t("steward.shell.page", { page: "overview" }), href: "/" }]
  let href = ""
  let section: string | undefined
  for (const segment of segments) {
    href += `/${segment}`
    const known = SECTIONS.has(segment)
    crumbs.push({
      label: known
        ? t("steward.shell.page", { page: segment })
        : (runLabel(section, segment) ??
          (section === "services" ? serviceTitle(readable(segment)) : readable(segment))),
      href,
    })
    section = known ? segment : undefined
  }
  const id = personId(pathname)
  if (id !== undefined && people) {
    const person = people.find((candidate) => candidate.discordId === id)
    crumbs[crumbs.length - 1].label = person ? personName(person) : t("steward.people.unknown-person")
  }
  return crumbs
}

export function useCrumbs(): Crumb[] {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const people = usePeople(personId(pathname) !== undefined)
  return breadcrumbsFor(pathname, people.data)
}

/** The brand `index.html` names the tab with, which the overview keeps. */
const BRAND = document.title

/** Names the browser tab after the deepest crumb, so every open page reads apart. */
export function useDocumentTitle(crumbs: Crumb[]) {
  const title = crumbs.length > 1 ? crumbs[crumbs.length - 1].label : BRAND
  useEffect(() => {
    document.title = title
  }, [title])
}
