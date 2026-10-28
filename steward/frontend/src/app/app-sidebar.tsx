import { Link, useRouterState } from "@tanstack/react-router"

import { useNavigation } from "@/app/navigation"
import { useServices, useTopology } from "@/lib/queries"
import { HealthDot } from "@/components/steward/status"
import { SkeletonText } from "@/components/ui/skeleton"
import { t } from "@/lib/texts"

const WAITING_ROWS = [0, 1, 2, 3]

export function NavList({ onFollow, marker }: { onFollow?: () => void; marker: "text" | "surface" }) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
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
            <div className="flex h-7 items-center pl-[calc((var(--control-min-height)-(--spacing(4)))/2)] text-xs text-muted-foreground">
              {group.label}
            </div>
          ) : null}
          <ul className="flex flex-col">
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
                    className={`flex min-h-control items-center rounded-lg pr-3 text-sm transition-colors duration-150 ease-out focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none ${rowState(isActive)}`}
                  >
                    <span className="flex size-control shrink-0 items-center justify-center">
                      <entry.icon className="size-4" aria-hidden />
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

export function resolveHref(to: string, params?: Record<string, string>) {
  if (!params) return to
  return Object.entries(params).reduce((path, [key, value]) => path.replace(`$${key}`, encodeURIComponent(value)), to)
}

/** A route matches by its fixed part, so Backups stays selected on a backup run's page. */
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

function fixedPart(to: string) {
  const parameter = to.indexOf("/$")
  return parameter === -1 ? to : to.slice(0, parameter)
}

function matches(pathname: string, href: string) {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(`${href}/`)
}
