import { Link, useRouterState } from "@tanstack/react-router"

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarSeparator,
} from "@/components/ui/sidebar"
import { NAVIGATION } from "@/app/navigation"
import { StewardMark } from "@/app/steward-mark"

/**
 * The navigation, always expanded.
 *
 * `collapsible="none"` is deliberate and is not a placeholder for a collapse button: this is an
 * operator's tool on a desktop, the labels are the point, and an icon rail that hides the word
 * "Restore" behind a play glyph is a worse interface, not a denser one.
 *
 * The active item is marked with a blue rule down its left edge and blue text - never a blue
 * background. Blue is action in this interface; a selected row is a place, not an action, so it
 * gets the brand's line and not the brand's surface.
 */
export function AppSidebar() {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  // Decided once for the whole navigation rather than per entry: "is this one active" cannot be
  // answered by looking at one entry, because two of them can match and only the longer is meant.
  const active = activeEntryId(pathname, NAVIGATION)

  return (
    <Sidebar collapsible="none" className="h-svh border-r">
      <SidebarHeader className="h-14 justify-center border-b px-cell">
        <Link
          to="/"
          className="flex items-center gap-2.5 rounded-md py-1 text-sm font-semibold tracking-tight transition-colors duration-150 ease-out hover:text-primary focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          <StewardMark className="size-5 shrink-0" />
          <span>
            Nordtal <span className="text-muted-foreground">Steward</span>
          </span>
        </Link>
      </SidebarHeader>

      <SidebarContent>
        {NAVIGATION.map((group) => (
          <SidebarGroup key={group.id}>
            <SidebarGroupLabel className="text-[0.6875rem] tracking-[0.08em] uppercase">
              {group.label}
            </SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                {group.entries.map((entry) => {
                  return (
                    <SidebarMenuItem key={entry.id}>
                      <SidebarMenuButton
                        asChild
                        isActive={entry.id === active}
                        tooltip={entry.note}
                        className="relative min-h-control data-[active=true]:bg-transparent data-[active=true]:text-primary data-[active=true]:before:absolute data-[active=true]:before:inset-y-1 data-[active=true]:before:-left-2 data-[active=true]:before:w-0.5 data-[active=true]:before:rounded-full data-[active=true]:before:bg-primary data-[active=true]:before:content-['']"
                      >
                        <Link
                          to={entry.to}
                          params={entry.params as never}
                          activeOptions={{ exact: entry.to === "/" }}
                        >
                          {entry.icon ? <entry.icon aria-hidden /> : null}
                          <span className="truncate">{entry.label}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  )
                })}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarSeparator className="mx-0" />
      <SidebarFooter className="px-cell py-3">
        <p className="text-xs text-muted-foreground">
          Alpha - no data yet.{" "}
          <kbd className="rounded-sm border border-border bg-secondary px-1 py-0.5 font-mono text-[0.6875rem] text-foreground">
            Strg
          </kbd>
          <span className="px-0.5">+</span>
          <kbd className="rounded-sm border border-border bg-secondary px-1 py-0.5 font-mono text-[0.6875rem] text-foreground">
            K
          </kbd>{" "}
          opens the search.
        </p>
      </SidebarFooter>
    </Sidebar>
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
