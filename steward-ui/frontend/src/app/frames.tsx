import { useState } from "react"
import type { ReactNode } from "react"
import { Link, Outlet, useRouterState } from "@tanstack/react-router"

import { AppSidebar, activeEntryId } from "@/app/app-sidebar"
import { useCrumbs } from "@/app/breadcrumbs"
import { MenuIsland, PathIsland, SearchButton, SearchRow } from "@/app/island"
import { NAVIGATION } from "@/app/navigation"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { useServices } from "@/lib/queries"
import { HealthDot } from "@/components/steward/status"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { ScrollArea } from "@/components/ui/scroll-area"
import { SidebarInset, useSidebar } from "@/components/ui/sidebar"

/**
 * The three shells of steward/89, side by side, chosen with `?shell=a|b|c`.
 *
 * All three obey the part of the order that is not a choice: no header and no header border in
 * either state, an island at the top left carrying the navigation toggle and the path, the picture
 * of the signed-in person level with it, and a sidebar that really collapses and remembers it.
 * What differs is the answer to one question - what the island does when the navigation is open -
 * and each shell answers it differently enough to be recognised from across a room.
 *
 * They are meant to be deleted. Two of them lose.
 */

/**
 * The scrolling column, which is the same in all three.
 *
 * The document itself never scrolls: the island stays put and only this moves. `top` is what a
 * shell needs to keep out from under its own island - nothing in two of them, where the island is
 * a row in the flow, and the height of the island in the one where it floats over the content.
 */
function Content({ top }: { top: string }) {
  return (
    <ScrollArea className="min-h-0 flex-1">
      <main
        className={`mx-auto w-full max-w-[110rem] px-4 pb-[max(1rem,env(safe-area-inset-bottom))] md:px-6 ${top}`}
      >
        <Outlet />
      </main>
    </ScrollArea>
  )
}

/** The row an island sits in: no border, no background, no height of its own. It is not a bar. */
function IslandRow({ children }: { children: ReactNode }) {
  return (
    <div className="flex shrink-0 items-center gap-2 px-4 pt-[calc(0.75rem+var(--blur-clearance))] pb-1 md:px-6">
      {children}
    </div>
  )
}

/**
 * SHELL A - the island unfolds into the sidebar.
 *
 * Collapsed, it is a pill at the top left with the toggle and the path. Open it and the pill is
 * gone: the toggle and the path are the head of the sidebar itself, which is where the eye already
 * is once a column of links is standing there. One object, in two places, never both at once.
 *
 * **Where the search went:** into a second island at the top right, beside the account. It is
 * the only shell of the three whose left-hand island disappears, so the search cannot live in it
 * without disappearing too - and a phone has no `⌘K` to fall back on. Top right is where the
 * header kept it, which is the one position a reader of this interface already knows.
 */
export function ShellA({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  // On a phone the sidebar is a sheet drawn over everything, so the island never moves there -
  // there is no head of a column for it to move into that the reader can see at the same time.
  const inSidebar = open && !isMobile

  return (
    <>
      <AppSidebar head={inSidebar ? { crumbs, onToggle: toggleSidebar } : undefined} />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <IslandRow>
          {inSidebar ? null : (
            <PathIsland
              crumbs={crumbs}
              expanded={false}
              onToggle={toggleSidebar}
              className="min-w-0 flex-1 sm:max-w-md"
            />
          )}
          <div className="ml-auto flex shrink-0 items-center gap-1 rounded-lg border border-border bg-card p-1">
            <SearchButton plain />
            <UserMenu me={me} plain />
          </div>
        </IslandRow>
        <Content top="pt-2 md:pt-3" />
      </SidebarInset>
    </>
  )
}

/**
 * SHELL B - the island never moves.
 *
 * It floats over the page at the top left, in the same place in both states, and the only thing
 * that changes is the panel icon turning over. The sidebar slides out from underneath it, so the
 * island keeps lying on top of the column while the page scrolls under both - nothing in the
 * layout jumps, ever, and the path is always at the same pixel.
 *
 * **Where the search went:** into the island, at its far end. This is the shell whose island is
 * the one object that never moves, which makes it the one place a search can be put and be found
 * there in every state and on every page. The account picture is then alone at the top right,
 * where a thumb reaches it.
 */
export function ShellB({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  const expanded = open && !isMobile

  return (
    <>
      <AppSidebar clearIsland />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        {/*
          Fixed to the window rather than placed in the column, and that is the whole shell: it is
          over the sidebar as well, which is what "the sidebar comes out from under it" means. The
          strip itself takes no pointer events, so the page underneath is reachable everywhere the
          two islands are not.
        */}
        <div className="pointer-events-none fixed inset-x-0 top-0 z-20 flex items-center gap-2 px-4 pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] md:px-6">
          <PathIsland
            crumbs={crumbs}
            expanded={expanded}
            onToggle={toggleSidebar}
            rotate
            floating
            trailing={<SearchButton plain />}
            className="pointer-events-auto min-w-0 flex-1 sm:max-w-md"
          />
          <div className="pointer-events-auto ml-auto shrink-0">
            <UserMenu me={me} />
          </div>
        </div>
        <Content top="pt-[4.5rem] md:pt-16" />
      </SidebarInset>
    </>
  )
}

/**
 * SHELL C - there is no column at all.
 *
 * The question the other two answer is what the island does when the sidebar opens. This one asks
 * whether there has to be a sidebar: the whole island is a button, and it drops the navigation
 * down as a panel over the page - the same object, the same gesture and the same size on a phone
 * and on a desktop, with the content never moving by a pixel and never losing a third of its
 * width. It also makes the path a control rather than a label: the target is the entire island,
 * about 300px wide on a phone, instead of a 44px square in the corner.
 *
 * **Where the search went:** it is the first row of that panel. In this shell the panel is the one
 * place you go to change page, and searching for a page is the same act as picking one from the
 * list - so it is the first thing in the list rather than a second control competing with it.
 * That costs one tap on a phone compared with the other two, and buys the panel being the only
 * thing anybody has to find.
 */
export function ShellC({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const [navOpen, setNavOpen] = useState(false)

  return (
    <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
      <IslandRow>
        <div className="min-w-0 flex-1 sm:max-w-md">
          <Popover open={navOpen} onOpenChange={setNavOpen}>
            <PopoverTrigger asChild>
              <MenuIsland crumbs={crumbs} expanded={navOpen} />
            </PopoverTrigger>
            <PopoverContent
              align="start"
              className="flex w-[min(20rem,calc(100vw-2rem))] flex-col gap-1 p-1"
            >
              <SearchRow onDone={() => setNavOpen(false)} />
              <NavPanel onFollow={() => setNavOpen(false)} />
            </PopoverContent>
          </Popover>
        </div>
        <div className="ml-auto shrink-0">
          <UserMenu me={me} />
        </div>
      </IslandRow>
      <Content top="pt-2 md:pt-3" />
    </SidebarInset>
  )
}

/**
 * The navigation as a panel: the same list the sidebar draws, in the one surface shell C has.
 *
 * It is written out here rather than borrowed from the sidebar because every sidebar primitive
 * carries the sidebar's own geometry and its hover tooltips, which belong to a column and not to a
 * panel. What it must not lose is the health dot on the ten container rows (steward/83) and the
 * rule that the longest match wins - both come from the sidebar's own module.
 *
 * Nothing in here is bordered: the panel is the surface.
 */
function NavPanel({ onFollow }: { onFollow: () => void }) {
  const services = useServices()
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const active = activeEntryId(pathname, NAVIGATION)

  return (
    <nav className="flex max-h-[calc(var(--app-height)-9rem)] flex-col gap-0.5 overflow-y-auto">
      {NAVIGATION.map((group) => (
        <div key={group.id} className="flex flex-col gap-0.5">
          {group.label ? (
            <span className="px-2 pt-2 text-[0.6875rem] text-muted-foreground">{group.label}</span>
          ) : null}
          {group.entries.map((entry) => {
            const Icon = entry.icon ?? group.icon
            const service =
              group.id === "services"
                ? services.data?.services.find((row) => row.service === entry.params?.name)
                : undefined
            return (
              <Link
                key={entry.id}
                to={entry.to}
                params={entry.params as never}
                onClick={onFollow}
                data-active={entry.id === active ? "true" : undefined}
                className="flex min-h-control items-center gap-2 rounded-md px-2 text-sm transition-colors duration-150 ease-out hover:bg-secondary focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none data-[active=true]:text-primary"
              >
                {Icon ? <Icon className="size-4 shrink-0" aria-hidden /> : null}
                <span className="min-w-0 flex-1 truncate">{entry.label}</span>
                {group.id === "services" ? <HealthDot service={service} /> : null}
              </Link>
            )
          })}
        </div>
      ))}
    </nav>
  )
}
