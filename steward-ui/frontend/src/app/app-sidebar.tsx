import { Link, useRouterState } from "@tanstack/react-router"

import { NAVIGATION } from "@/app/navigation"
import { useServices } from "@/lib/queries"
import { HealthDot } from "@/components/steward/status"

/**
 * The list of places, the one thing the desktop's column and the phone's dock both hold.
 *
 * **Every row is a box of `size-control` and a label, and the box is the column.** The toggle
 * above the list is the same box on the same line, so the toggle and every row's icon stand one
 * above the other, and the mark beside the toggle starts where every label starts. A group heading
 * is indented by half of what the box has around its 16px glyph, so its first letter stands on the
 * glyph's edge rather than on the box's. That is the whole of the grid, and the spacing complaint
 * that retired the last sidebar (uneven gaps around "Steward" and "Overview") was three elements
 * each measuring from an edge of its own.
 *
 * **The marker differs by device, and that is Till's pick rather than an oversight** (2026-09-24):
 * the desktop's column marks the page with blue text, as it always has; the phone's dock with a
 * muted surface, which reads better under a thumb that is about to cover the text.
 *
 * **Every service row carries a health dot** (steward/83). `useServices()` is the one query this
 * opens, and it is the same query every page shares, so it does not add a second poll.
 */
export function NavList({ onFollow, marker }: { onFollow?: () => void; marker: "text" | "surface" }) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  // Decided once for the whole navigation rather than per entry: "is this one active" cannot be
  // answered by looking at one entry, because two of them can match and only the longer is meant.
  const active = activeEntryId(pathname, NAVIGATION)
  const services = useServices()

  const rowState = (isActive: boolean) =>
    marker === "text"
      ? isActive
        ? "text-primary"
        : "text-foreground/85 hover:bg-secondary/50 hover:text-foreground"
      : isActive
        ? "bg-secondary text-foreground"
        : "text-foreground/85 hover:bg-secondary/50 hover:text-foreground"

  return (
    <nav aria-label="Pages" className="flex flex-col gap-4">
      {NAVIGATION.map((group) => (
        <div key={group.id} className="flex flex-col">
          {group.label ? (
            <div className="flex h-7 items-center pl-[calc((var(--control-min-height)-1rem)/2)] text-xs text-muted-foreground">
              {group.label}
            </div>
          ) : null}
          <ul className="flex flex-col">
            {group.entries.map((entry) => {
              const isActive = entry.id === active
              // Only the ten container rows carry a dot. `entry.params.name` is the exact service
              // name the query answers with, because `navigation.ts` built the params from it.
              const service =
                group.id === "services"
                  ? services.data?.services.find((row) => row.service === entry.params?.name)
                  : undefined
              return (
                <li key={entry.id}>
                  <Link
                    to={entry.to}
                    params={entry.params as never}
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
  return Object.entries(params).reduce(
    (path, [key, value]) => path.replace(`$${key}`, encodeURIComponent(value)),
    to,
  )
}

/**
 * Which single entry a path lights up.
 *
 * <p>Two entries can match one path - {@code /operations/plan} matches both "Overview"
 * ({@code /operations}) and "Plan" - and the sidebar then showed two selected rows with no way to
 * tell which page you were on. The rule is the longest match wins, decided across the whole
 * navigation, which is why this cannot be a predicate on one entry.</p>
 *
 * <p>A parameterised route is matched by its fixed part, not by the link it happens to point at:
 * "Run" links to {@code /operations/runs/latest} and must still be the selected row while you are
 * reading run 27. A per-service entry carries a real name in its parameters, and that longer
 * match is what keeps the right service selected rather than all of them.</p>
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

/** Everything before a route's first parameter: `/operations/runs/$id` is `/operations/runs`. */
function fixedPart(to: string) {
  const parameter = to.indexOf("/$")
  return parameter === -1 ? to : to.slice(0, parameter)
}

function matches(pathname: string, href: string) {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(`${href}/`)
}
