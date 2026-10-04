import { Link, useRouterState } from "@tanstack/react-router"

import { useNavigation } from "@/app/navigation"
import { useServices, useTopology } from "@/lib/queries"
import { HealthDot } from "@/components/steward/status"
import { SkeletonText } from "@/components/ui/skeleton"
import { t } from "@/lib/texts"

/** Four rows while the services are read, about as many as Paper and the entry have. */
const WAITING_ROWS = [0, 1, 2, 3]

/**
 * The list of places, held by the desktop's column and the phone's dock alike.
 *
 * Every row is a `size-control` box and a label, so icons and labels align with the toggle above.
 */
export function NavList({ onFollow, marker }: { onFollow?: () => void; marker: "text" | "surface" }) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  /** Decided across the whole navigation, since two entries can match and only the longer is meant. */
  const groups = useNavigation()
  const active = activeEntryId(pathname, groups)
  const services = useServices()
  const topology = useTopology()

  const rowState = (isActive: boolean) =>
    marker === "text"
      ? isActive
        ? "text-primary"
        : "text-foreground/85 hover:bg-secondary/50 hover:text-foreground"
      : isActive
        ? "bg-secondary text-foreground"
        : "text-foreground/85 hover:bg-secondary/50 hover:text-foreground"

  return (
    <nav aria-label={t("steward.shell.pages")} className="flex flex-col gap-4">
      {groups.map((group) => (
        <div key={group.id} className="flex flex-col">
          {group.label ? (
            <div className="flex h-7 items-center pl-[calc((var(--control-min-height)-1rem)/2)] text-xs text-muted-foreground">
              {group.label}
            </div>
          ) : null}
          <ul className="flex flex-col">
            {/* The service rows are the served topology's, so they wait for it as rows of their own shape. */}
            {group.id === "services" && topology.isPending
              ? WAITING_ROWS.map((index) => (
                  <li key={index} className="flex min-h-control items-center pr-3">
                    <span className="size-control shrink-0" />
                    <SkeletonText width="short" className="text-sm" />
                  </li>
                ))
              : null}
            {group.entries.map((entry) => {
              const isActive = entry.id === active
              /**
               * Only the container rows carry a dot, keyed by the service name `navigation.ts` put in the params.
               */
              const service =
                group.id === "services"
                  ? services.data?.services.find((row) => row.service === entry.params?.name)
                  : undefined
              return (
                <li key={entry.id}>
                  <Link
                    to={entry.to}
                    params={entry.params}
                    onClick={onFollow}
                    aria-current={isActive ? "page" : undefined}
                    className={`flex min-h-control items-center rounded-lg pr-3 text-sm transition-colors duration-150 ease-out focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none ${rowState(isActive)}`}
                  >
                    <span className="flex size-control shrink-0 items-center justify-center">
                      {entry.icon ? <entry.icon className="size-4" aria-hidden /> : null}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{entry.label}</span>
                    {group.id === "services" ? <HealthDot service={service} /> : null}
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </nav>
  )
}

/** Substitutes `$name`-style segments, so one entry can describe a parameterised route. */
export function resolveHref(to: string, params?: Record<string, string>) {
  if (!params) return to
  return Object.entries(params).reduce((path, [key, value]) => path.replace(`$${key}`, encodeURIComponent(value)), to)
}

/**
 * The single entry a path lights up: the longest fixed part wins across the whole navigation.
 *
 * A route matches by its fixed part, so "Backups" stays selected on a backup run's page.
 */
export function activeEntryId(
  pathname: string,
  groups: readonly { entries: readonly { id: string; to: string; params?: Record<string, string> }[] }[],
) {
  let best: string | null = null
  let longest = -1
  for (const group of groups) {
    for (const entry of group.entries) {
      const candidates = [resolveHref(entry.to, entry.params), fixedPart(entry.to)]
      for (const candidate of candidates) {
        if (matches(pathname, candidate) && candidate.length > longest) {
          best = entry.id
          longest = candidate.length
        }
      }
    }
  }
  return best
}

/** Everything before a route's first parameter: `/operations/updates/$id` is `/operations/updates`. */
function fixedPart(to: string) {
  const parameter = to.indexOf("/$")
  return parameter === -1 ? to : to.slice(0, parameter)
}

function matches(pathname: string, href: string) {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(`${href}/`)
}
