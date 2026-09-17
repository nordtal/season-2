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
  useSidebar,
} from "@/components/ui/sidebar"
import type { Crumb } from "@/app/breadcrumbs"
import { Crumbs, SidebarToggle } from "@/app/island"
import { NAVIGATION } from "@/app/navigation"
import { StewardMark } from "@/app/steward-mark"
import { shortcutLabel } from "@/lib/keys"
import { useServices } from "@/lib/queries"
import { HealthDot } from "@/components/steward/status"

/**
 * The navigation.
 *
 * **On a desktop it collapses, all the way out** (steward/89). It used to be held open, on the
 * argument that an icon rail hiding the word "Restore" behind a play glyph is a worse interface
 * rather than a denser one - and that argument survives: `collapsible` is `offcanvas`, so a
 * collapsed sidebar is gone rather than reduced to glyphs, and the island at the top left is what
 * brings it back. The state is kept in the provider's cookie, so it survives a reload.
 *
 * **On a phone it is a sheet**, because 13rem of a 390px screen is a third of it. `collapsible` is
 * `offcanvas` rather than `none` for exactly one reason: `none` renders a plain column and skips
 * the mobile branch entirely, which is why this sidebar used to stand beside the content on a phone
 * and leave about 150px for the page. The sheet has its own state (`openMobile`), so switching this
 * changes nothing at all about the desktop.
 *
 * The active item is marked with a blue rule down its left edge and blue text - never a blue
 * background. Blue is action in this interface; a selected row is a place, not an action, so it
 * gets the brand's line and not the brand's surface.
 *
 * **Every service row carries a health dot** (steward/83), drawn by the same {@link HealthDot}
 * steward/81's network view will use. `useServices()` is the one query this component opens - the
 * hook already carries its own ten-second `refetchInterval`, so mounting it here does not add a
 * second poll: every page shares the one query behind `useServices`, this component included.
 */
export type AppSidebarProps = {
  /**
   * The island, unfolded into the head of this column - shell A of steward/89 and nothing else.
   * Absent means the island is standing somewhere else and this head is only the brand.
   */
  head?: { crumbs: Crumb[]; onToggle: () => void }
  /**
   * Room at the top for an island floating over this column - shell B, where the sidebar slides
   * out from underneath one. Without it the brand is drawn behind the island.
   */
  clearIsland?: boolean
}

export function AppSidebar({ head, clearIsland }: AppSidebarProps = {}) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  // Decided once for the whole navigation rather than per entry: "is this one active" cannot be
  // answered by looking at one entry, because two of them can match and only the longer is meant.
  const active = activeEntryId(pathname, NAVIGATION)
  const { isMobile, setOpenMobile } = useSidebar()
  const services = useServices()

  // Tapping a place closes the sheet. Without this the phone lands on the new page with the
  // navigation still over it, and the first thing every visit needs is a tap somewhere empty.
  const follow = () => {
    if (isMobile) setOpenMobile(false)
  }

  return (
    <Sidebar collapsible="offcanvas" className="h-(--app-height) border-r">
      <SidebarHeader
        className={`justify-center gap-1 border-b px-cell py-2 ${clearIsland ? "pt-[4.5rem]" : ""}`}
      >
        <Link
          to="/"
          onClick={follow}
          className="flex items-center gap-2.5 rounded-md py-1 text-sm font-semibold tracking-tight transition-colors duration-150 ease-out hover:text-primary focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          <StewardMark className="size-5 shrink-0" />
          <span>
            Nordtal <span className="text-muted-foreground">Steward</span>
          </span>
        </Link>
        {/*
          The island, unfolded (steward/89, shell A). It is not drawn as a pill here: the sidebar
          is already a surface with an edge, and a bordered pill inside it would be the nesting
          Till's rule forbids. So the toggle and the path simply are the second line of this head.
        */}
        {head ? (
          <div className="flex min-w-0 items-center gap-0.5">
            <SidebarToggle expanded onToggle={head.onToggle} className="-ml-1" />
            <div className="min-w-0 flex-1 px-1">
              <Crumbs crumbs={head.crumbs} />
            </div>
          </div>
        ) : null}
      </SidebarHeader>

      <SidebarContent className="gap-0">
        {NAVIGATION.map((group) => (
          <SidebarGroup key={group.id} className="py-1.5">
            {group.label ? (
              <SidebarGroupLabel className="h-6 text-[0.6875rem]">
                {group.label}
              </SidebarGroupLabel>
            ) : null}
            <SidebarGroupContent>
              <SidebarMenu>
                {group.entries.map((entry) => {
                  // Only the ten container rows carry a dot - the health this section is about.
                  // `entry.params.name` is the exact service name the query answers with, because
                  // it is the same name `navigation.ts` built the route's params from.
                  const service =
                    group.id === "services"
                      ? services.data?.services.find((row) => row.service === entry.params?.name)
                      : undefined
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
                          onClick={follow}
                        >
                          {entry.icon ? <entry.icon aria-hidden /> : null}
                          <span className="min-w-0 flex-1 truncate">{entry.label}</span>
                          {group.id === "services" ? (
                            <HealthDot service={service} className="ml-auto" />
                          ) : null}
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

      {/*
        One line, and only on a machine that has the key - which is why it is not here on a phone at
        all. It said "Alpha - no data yet." beside an interface full of the host's real numbers, and
        then repeated a shortcut the header already carries. What is left is the shortcut itself, in
        the notation of whatever is reading it, offered to the only kind of device that has it.
      */}
      {isMobile ? null : (
        <>
          <SidebarSeparator className="mx-0" />
          <SidebarFooter className="px-cell py-2 pb-[max(0.5rem,env(safe-area-inset-bottom))]">
            <p className="text-xs text-muted-foreground">
              <kbd className="rounded-sm border border-border bg-secondary px-1 py-0.5 font-mono text-[0.6875rem] text-foreground">
                {shortcutLabel("K")}
              </kbd>{" "}
              searches every page.
            </p>
          </SidebarFooter>
        </>
      )}
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
