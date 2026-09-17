import { SidebarIcon } from "@phosphor-icons/react"
import { useState } from "react"
import type { ReactNode } from "react"
import { Link, Outlet, useRouterState } from "@tanstack/react-router"

import { AppSidebar, activeEntryId } from "@/app/app-sidebar"
import type { Crumb } from "@/app/breadcrumbs"
import { useCrumbs } from "@/app/breadcrumbs"
import {
  Crumbs,
  FixedCorner,
  IslandSurface,
  MenuIsland,
  PathIsland,
  SearchButton,
  SearchRow,
  SidebarToggle,
} from "@/app/island"
import { NAVIGATION } from "@/app/navigation"
import { StewardMark } from "@/app/steward-mark"
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
 * SHELL D - the toggle stands outside the island, fixed to the corner.
 *
 * Till, 2026-09-17: a middle ground between the three first shells, ordered with one requirement
 * named against B by number - the toggle has to sit at the same pixel in both states, not just look
 * like it does. B drew two different buttons in two different places that happened to look alike.
 * Here there is only ever one: a bordered square, fixed to the top left corner for the life of the
 * page, never inside the island and never swapped for a differently-positioned twin when the sidebar
 * opens. The island beside it carries nothing but the mark, "Steward" and the path - `brand` on
 * {@link Crumbs} draws those instead of "Overview" - and unfolds into the head of the column exactly
 * as shell A's did, minus the button that used to move with it.
 */
export function ShellD({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  const inSidebar = open && !isMobile

  return (
    <>
      <FixedCorner>
        <div className="pointer-events-auto flex size-control shrink-0 items-center justify-center rounded-lg border border-border bg-card shadow-sm">
          <SidebarToggle expanded={inSidebar} onToggle={toggleSidebar} rotate />
        </div>
      </FixedCorner>
      <AppSidebar
        head={
          inSidebar
            ? { crumbs, onToggle: toggleSidebar, brand: true, hideToggle: true }
            : undefined
        }
      />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <IslandRow>
          {inSidebar ? null : (
            <PathIsland
              crumbs={crumbs}
              expanded={false}
              onToggle={toggleSidebar}
              brand
              hideToggle
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
 * SHELL E - the mark is the toggle.
 *
 * Till's own suggestion, 2026-09-17: no separate panel glyph at all - the Nordtal mark carries the
 * toggle, and answers a hover or a focus by fading to the panel icon, so the change of mind is
 * visible before it is clicked. **Two reachable targets, not one control pretending to be two** -
 * the decision the order asked to be written down rather than assumed: the mark toggles and does
 * nothing else, "Steward" beside it is a plain link to `/` and does nothing else. A screen reader
 * meets a button named "Navigation", and right after it in the tab order, a link named "Steward" -
 * never a single element doing both jobs behind one label.
 *
 * The pair is fixed to the left edge for the life of the page, exactly like shell D's square, so the
 * same requirement holds the same way. What changes under it is the background: a floating card
 * while the sidebar is closed, and the sidebar's own colour with its own bottom border once it
 * opens - the corner the sidebar will occupy is the corner this already sits in, so nothing needs to
 * slide sideways to meet it, only recolour. **The path is a second line under it in every state**,
 * which is this shell's honestly-named cost: unlike d/f/g it does not unfold into the column, because
 * folding it in would put it beside a pair whose width depends on the word "Steward" rather than on
 * a fixed icon, and no fixed line-up could be relied on across every service name.
 */
function LogoToggle({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label="Navigation"
      aria-expanded={expanded}
      className="group relative flex size-control shrink-0 items-center justify-center rounded-md transition-colors duration-150 ease-out hover:bg-secondary/60 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <StewardMark className="size-5 shrink-0 transition-opacity duration-150 ease-out group-hover:opacity-0 group-focus-visible:opacity-0" />
      <SidebarIcon
        className={`absolute size-4 opacity-0 transition-opacity duration-150 ease-out group-hover:opacity-100 group-focus-visible:opacity-100 ${expanded ? "rotate-180" : ""}`}
        aria-hidden
      />
    </button>
  )
}

function BrandPill({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  return (
    <div
      className={`pointer-events-auto fixed left-0 top-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] z-30 flex items-center gap-1 border p-1 transition-colors duration-200 ease-out ${
        expanded
          ? "rounded-none border-x-0 border-t-0 border-b-sidebar-border bg-sidebar text-sidebar-foreground"
          : "rounded-r-lg border-border bg-card text-foreground shadow-sm"
      }`}
    >
      <LogoToggle expanded={expanded} onToggle={onToggle} />
      <Link
        to="/"
        className="flex h-control shrink-0 items-center rounded-md px-2 text-sm font-medium whitespace-nowrap transition-colors duration-150 ease-out hover:bg-secondary/60 focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        Steward
      </Link>
    </div>
  )
}

export function ShellE({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const rest = crumbs.slice(1)
  const { open, isMobile, toggleSidebar } = useSidebar()
  const inSidebar = open && !isMobile

  return (
    <>
      <BrandPill expanded={inSidebar} onToggle={toggleSidebar} />
      <AppSidebar clearIsland hideBrandLink />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <div className="pointer-events-none fixed inset-x-0 top-0 z-30 flex justify-end px-4 pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] md:px-6">
          <div className="pointer-events-auto flex shrink-0 items-center gap-1 rounded-lg border border-border bg-card p-1">
            <SearchButton plain />
            <UserMenu me={me} plain />
          </div>
        </div>
        {rest.length > 0 ? (
          <div className="flex shrink-0 items-center gap-2 px-4 pt-[3.5rem] pb-1 md:px-6">
            <IslandSurface className="min-w-0 flex-1 sm:max-w-md">
              <div className="min-w-0 flex-1 px-1">
                <Crumbs crumbs={rest} />
              </div>
            </IslandSurface>
          </div>
        ) : null}
        <Content top={rest.length > 0 ? "pt-1" : "pt-[3.5rem]"} />
      </SidebarInset>
    </>
  )
}

/**
 * SHELL F - the island and the head of the column are the same element.
 *
 * The cleanest reading of "nahtlos" (Till, 2026-09-17): not two surfaces that happen to line up, one
 * surface that changes size. It is fixed flush to the left edge in every state - collapsed, it is a
 * small floating card because there is no column there yet to align with; expanded, it widens to the
 * exact width of the column and swaps its rounded corners and shadow for the flat top edge and the
 * border the column already has, so the seam is not hidden, it does not exist. The toggle inside it
 * never moves because the element's own anchor - its left and top edge - never moves either; only
 * its right and bottom edges do.
 */
function GrowingHead({
  crumbs,
  expanded,
  onToggle,
  bleed = false,
}: {
  crumbs: Crumb[]
  expanded: boolean
  onToggle: () => void
  /**
   * Stop capping the expanded width at the sidebar's own `13rem` and let it size to the chain
   * instead (shell i, steward/89, 2026-09-17) - see {@link ShellI}'s own comment for why `w-(--sidebar-width)`
   * was the wrong fixed point rather than a fact about the column.
   */
  bleed?: boolean
}) {
  return (
    <div
      className={`pointer-events-auto fixed left-0 top-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] z-30 flex items-center gap-0.5 border p-1 transition-[width,border-radius,box-shadow,background-color] duration-200 ease-out ${
        expanded
          ? bleed
            ? "w-fit max-w-[calc(100vw-1.5rem)] rounded-none rounded-r-lg border-x-0 border-t-0 border-b-sidebar-border bg-sidebar text-sidebar-foreground shadow-none"
            : "w-(--sidebar-width) rounded-none border-x-0 border-t-0 border-b-sidebar-border bg-sidebar text-sidebar-foreground shadow-none"
          : "w-[min(20rem,calc(100vw-2rem))] rounded-lg border-border bg-card text-foreground shadow-sm"
      }`}
    >
      <SidebarToggle expanded={expanded} onToggle={onToggle} rotate />
      {/*
        `flex-1` only when the container's own width is fixed (`w-(--sidebar-width)`): that is what
        makes the shrink rule inside `Crumbs` see the column's real remaining width. A `bleed`d head
        has no fixed width to fill - it is sized to its content up to `max-w` - so forcing growth
        here would fight that sizing instead of feeding it.
      */}
      <div className={bleed ? "min-w-0 px-1" : "min-w-0 flex-1 px-1"}>
        <Crumbs crumbs={crumbs} brand />
      </div>
    </div>
  )
}

export function ShellF({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  const morph = open && !isMobile

  return (
    <>
      <AppSidebar clearIsland hideBrandLink />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <GrowingHead crumbs={crumbs} expanded={morph} onToggle={toggleSidebar} />
        <div className="pointer-events-none fixed inset-x-0 top-0 z-30 flex justify-end px-4 pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] md:px-6">
          <div className="pointer-events-auto flex shrink-0 items-center gap-1 rounded-lg border border-border bg-card p-1">
            <SearchButton plain />
            <UserMenu me={me} plain />
          </div>
        </div>
        <Content top="pt-[4.5rem] md:pt-16" />
      </SidebarInset>
    </>
  )
}

/**
 * SHELL I - shell f, grown past the column instead of capped at it.
 *
 * The orchestrator's own screenshots, 2026-09-17: f's single growing element is the cleanest
 * reading of "nahtlos" there is, but it inherited a width that was never actually a requirement -
 * `w-(--sidebar-width)` is what the *column* measures, not what the chain needs, and pinning the
 * grown head to it is what forced "Steward › Services › smp" down to an ellipsis on both ends at
 * three levels. Nothing about "the island and the column's head are one element" requires that
 * element to stop at 13rem once it is free of the column's own box entirely (`position: fixed`
 * already lifted it out) - so here it does not: `bleed` on {@link GrowingHead} sizes it to the
 * chain instead, up to the viewport, and only the existing shrink rule - ancestors first - steps in
 * beyond that. The left edge, the top edge and the toggle inside it stay exactly where f's did.
 */
export function ShellI({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  const morph = open && !isMobile

  return (
    <>
      <AppSidebar clearIsland hideBrandLink />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <GrowingHead crumbs={crumbs} expanded={morph} onToggle={toggleSidebar} bleed />
        <div className="pointer-events-none fixed inset-x-0 top-0 z-30 flex justify-end px-4 pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] md:px-6">
          <div className="pointer-events-auto flex shrink-0 items-center gap-1 rounded-lg border border-border bg-card p-1">
            <SearchButton plain />
            <UserMenu me={me} plain />
          </div>
        </div>
        <Content top="pt-[4.5rem] md:pt-16" />
      </SidebarInset>
    </>
  )
}

/**
 * SHELL G - the toggle is a handle on the edge, nowhere near the island.
 *
 * Where d keeps the toggle beside the island, g puts it somewhere the island could never reach: a
 * handle fixed to the middle of the left edge, the height of a grip rather than a button, answering
 * only to "Navigation". By construction it cannot move, because nothing about it - position or
 * size - is a function of the sidebar's state beyond the arrow's own direction, and it is not a
 * sibling of the island in the markup the way d's square is. The island itself is purely the mark,
 * "Steward" and the path - no toggle to leave out - and unfolds into the column the same way d's
 * does.
 */
export function ShellG({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  const inSidebar = open && !isMobile

  return (
    <>
      {/*
        Moved here 2026-09-17, after the orchestrator's own screenshots caught it sitting on top of
        "steward-worker" in the service list: `top-1/2` measures the *viewport*, not the sidebar's
        own header, and a fixed handle at the window's vertical centre lands wherever the nav list's
        scroll position happens to put a row that day. The header does not scroll - it is the one
        band of the column that never moves under it - so anchoring to the same clearance every
        other corner element uses puts the handle over nothing else, ever, at any scroll position.
      */}
      <button
        type="button"
        onClick={toggleSidebar}
        aria-label="Navigation"
        aria-expanded={inSidebar}
        className="fixed top-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] left-0 z-30 flex h-9 w-5 items-center justify-center rounded-r-md border border-l-0 border-border bg-card text-muted-foreground shadow-sm transition-colors duration-150 ease-out hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <SidebarIcon
          className={`size-3.5 transition-transform duration-200 ease-out ${inSidebar ? "rotate-180" : ""}`}
          aria-hidden
        />
      </button>
      <AppSidebar
        head={
          inSidebar
            ? { crumbs, onToggle: toggleSidebar, brand: true, hideToggle: true }
            : undefined
        }
      />
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <IslandRow>
          {inSidebar ? null : (
            <IslandSurface className="min-w-0 flex-1 sm:max-w-md">
              <div className="min-w-0 flex-1 px-1">
                <Crumbs crumbs={crumbs} brand />
              </div>
            </IslandSurface>
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
 * The chain, sized to itself rather than to the column - shell h's own answer to the same fix as
 * shell i, kept as a separate element from the fixed corner icon on purpose (see {@link ShellH}).
 */
function WideHead({ crumbs }: { crumbs: Crumb[] }) {
  return (
    // `pointer-events-none` on the row itself, not just the spacer inside it (steward/89,
    // 2026-09-17): the first attempt left the row at `-auto` and only excused the blank span, which
    // stopped the span from catching the click but not the row underneath it - a parent's own box
    // still intercepts a click landing in space its child has opted out of. The preview tool's own
    // click caught this; jsdom cannot, because `fireEvent.click` never hit-tests. Only the crumbs -
    // the one part of this row with somewhere to go - opt back in, the same pattern e/f/g already
    // use for their own fixed rows.
    <div className="pointer-events-none fixed left-0 top-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] z-30 flex w-fit max-w-[calc(100vw-1.5rem)] items-center gap-0.5 rounded-r-lg border-b border-sidebar-border bg-sidebar px-4 py-2 text-sidebar-foreground md:px-6">
      {/* Blank rather than a second toggle - the real, clickable one is d's fixed corner square, directly underneath this rectangle. */}
      <span className="size-control shrink-0" aria-hidden />
      <div className="pointer-events-auto min-w-0 px-1">
        <Crumbs crumbs={crumbs} brand />
      </div>
    </div>
  )
}

/**
 * SHELL H - shell d, with the head sized to the chain instead of to the column.
 *
 * The orchestrator's own screenshots, 2026-09-17: d satisfies the one hard requirement Till named
 * by number - the corner icon never moves - but folding the chain into the sidebar's own `13rem`
 * head does not just shorten it, it drops whole segments at three levels: `Steward › Services › smp`
 * came back as `Steward › ›` with "Services" and "smp" both gone. The diagnosis was right (the
 * ancestors give way first, and there was nothing left to give) but the conclusion was wrong: the
 * fix is not a smarter shrink order, it is not pretending the head has to be `13rem` at all. It
 * never has to be - the head was never really part of the sidebar's own box to begin with, it only
 * looked that way because it shared a padding value with a component that happened to be `13rem`
 * wide. Lifted out into its own `position: fixed` element the way b's island always was, it can be
 * exactly as wide as the chain needs, bleeding past the column's own right edge into the content
 * underneath it - one continuous surface, flat on the left where it meets the column, rounded on
 * the right where it does not, and clipped by nothing narrower than the window itself.
 *
 * The corner icon is untouched from d: same square, same fixed position, same proof that it cannot
 * move. What changed is everything to its right.
 */
export function ShellH({ me }: { me: Me }) {
  const crumbs = useCrumbs()
  const { open, isMobile, toggleSidebar } = useSidebar()
  const inSidebar = open && !isMobile

  return (
    <>
      <AppSidebar clearIsland={inSidebar} hideBrandLink />
      {/*
        The head, then the icon on top of it - not the other way round (steward/89, 2026-09-17).
        Both are `position: fixed` at the same `z-30`, so painting order falls back to document
        order and whichever is later wins the pixels: with the icon first, its own opaque background
        painted first and the head's own opaque background painted second erased it completely in
        the very state - expanded - the whole point was to prove stays put. The `pointer-events`
        split above keeps the click routed to the real button regardless of paint order, but the
        pixels still need the icon painted last to stay visible at all.
      */}
      {inSidebar ? <WideHead crumbs={crumbs} /> : null}
      <FixedCorner>
        <div className="pointer-events-auto flex size-control shrink-0 items-center justify-center rounded-lg border border-border bg-card shadow-sm">
          <SidebarToggle expanded={inSidebar} onToggle={toggleSidebar} rotate />
        </div>
      </FixedCorner>
      <SidebarInset className="flex h-(--app-height) min-w-0 flex-col overflow-hidden bg-background pt-[env(safe-area-inset-top)]">
        <IslandRow>
          {inSidebar ? null : (
            <PathIsland
              crumbs={crumbs}
              expanded={false}
              onToggle={toggleSidebar}
              brand
              hideToggle
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
