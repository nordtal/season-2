import { Outlet, useRouterState } from "@tanstack/react-router"
import { useEffect } from "react"

import { NavList, activeEntryId } from "@/app/app-sidebar"
import { useCrumbs } from "@/app/breadcrumbs"
import { Brand, MOTION, NavToggle, SearchButton, Trail } from "@/app/island"
import { NAVIGATION } from "@/app/navigation"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { ScrollArea } from "@/components/ui/scroll-area"
import { useSidebar } from "@/components/ui/sidebar"

/**
 * The frame everything signed-in is drawn inside.
 *
 * <h2>Two shapes, picked out of four</h2>
 * The last sidebar looked unfinished - uneven gaps, an island that jumped as the column opened -
 * and four directions were built side by side for Till to hold a phone and compare. He picked two
 * of them on 2026-09-24, one per kind of screen: the island at the top left over a column that
 * slides in beneath it for a desktop or a tablet ({@link DesktopFrame}), and a dock at the bottom
 * that grows upward into the navigation for a phone ({@link PhoneFrame}). The two he did not pick
 * are deleted rather than kept behind a query parameter; what they tried is in the ticket.
 *
 * The line between them is `useIsMobile`'s 768px, the same line the sidebar provider already keeps
 * its two open states apart by: `open` for the column, `openMobile` for the dock.
 *
 * <h2>One axis</h2>
 * Everything measures from `--gutter`, the page content's own margin: the island's left edge, the
 * dock's two edges, the column's list and the content. The toggle, each row's icon and the first
 * letter of a page title therefore stand on lines that do not depend on which state the
 * navigation is in.
 *
 * <h2>What is fixed and what scrolls</h2>
 * The document itself never scrolls. The chrome is fixed and only {@link Content} moves, so an
 * operator watching a log window and a service table at the same time does not lose their place.
 *
 * <h2>Where the search is</h2>
 * Beside the account, on both devices: top right on a desktop, in the dock on a phone. A phone has
 * no `⌘K`, so a frame with nothing to tap has no command palette at all.
 */
export function AppFrame({ me }: { me: Me }) {
  const nav = useNav()
  return (
    <div className="flex h-(--app-height) w-full bg-background [--col:15rem] [--gutter:1rem] md:[--gutter:1.5rem]">
      {nav.isMobile ? <PhoneFrame me={me} nav={nav} /> : <DesktopFrame me={me} nav={nav} />}
    </div>
  )
}

type Nav = ReturnType<typeof useNav>

/** Whether the navigation is showing on this device, and the ways to change it. */
function useNav() {
  const { open, openMobile, isMobile, setOpenMobile, setOpen, toggleSidebar } = useSidebar()
  const shown = isMobile ? openMobile : open

  // Escape closes whatever is open, on both devices.
  useEffect(() => {
    if (!shown) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return
      if (isMobile) setOpenMobile(false)
      else setOpen(false)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [shown, isMobile, setOpenMobile, setOpen])

  return {
    shown,
    isMobile,
    toggle: toggleSidebar,
    // Tapping a place closes the dock. The column stays: it is beside the page, not over it.
    follow: () => {
      if (isMobile) setOpenMobile(false)
    },
    close: () => setOpenMobile(false),
  }
}

/**
 * THE DESKTOP AND THE TABLET: an island that never moves, over a column that slides in beneath it.
 *
 * Closed, the island is a small bordered surface carrying the toggle, the mark and the path of the
 * page. Opening the navigation does not move or resize anything in it: the column slides in under
 * it, the island drops its surface and the path folds away, so what is left - toggle and mark - is
 * the head of the column without ever having been a second thing. The toggle is where the pointer
 * already is, in both states.
 *
 * The list starts one group gap below the island's row: the head is spaced from "Overview" exactly
 * as "Overview" is spaced from "Services", which is the evenness the first round lacked.
 */
function DesktopFrame({ me, nav }: { me: Me; nav: Nav }) {
  const { shown, toggle } = nav
  const crumbs = useCrumbs().slice(1)

  return (
    <>
      <aside
        aria-hidden={!shown}
        inert={!shown}
        className={`fixed inset-y-0 left-0 z-40 flex w-(--col) flex-col border-r border-sidebar-border bg-sidebar transition-transform ${MOTION} ${shown ? "translate-x-0" : "-translate-x-full"}`}
      >
        {/* The island's border and padding are 5px; the list's boxes start on the toggle's box. */}
        <ScrollArea className="min-h-0 flex-1">
          <div className="pt-[calc(var(--island-top)+5px+var(--control-min-height)+1rem)] pr-3 pb-[max(1rem,env(safe-area-inset-bottom))] pl-[calc(var(--gutter)+5px)]">
            <NavList marker="text" />
          </div>
        </ScrollArea>
      </aside>

      {/* The island. Fixed, never moves; only its surface and the path come and go. */}
      <div
        className={`fixed top-(--island-top) left-(--gutter) z-50 flex max-w-[calc(100vw-2*var(--gutter)-7rem)] items-center rounded-xl border p-1 transition-[background-color,border-color,box-shadow] ${MOTION} ${
          shown ? "border-transparent bg-transparent shadow-none" : "border-border bg-card shadow-sm"
        }`}
      >
        <NavToggle shown={shown} onToggle={toggle} glyph="panel" />
        <Brand />
        {/*
          While the column is open the list answers "where am I" better than a trail does, and two
          answers to one question is the weaker one winning half the time - so the path folds away.
        */}
        <div
          aria-hidden={shown}
          inert={shown}
          data-trail
          className={`flex min-w-0 overflow-hidden transition-[max-width,opacity] ${MOTION} ${
            shown ? "max-w-0 opacity-0" : "max-w-[32rem] pr-2 opacity-100"
          }`}
        >
          <Trail crumbs={crumbs} />
        </div>
      </div>

      <div className="fixed top-(--island-top) right-(--gutter) z-30 flex items-center gap-1 rounded-xl border border-border bg-card p-1 shadow-sm">
        <SearchButton />
        <UserMenu me={me} plain />
      </div>

      <div
        className={`flex min-w-0 flex-1 flex-col transition-[margin] ${MOTION}`}
        style={{ marginLeft: shown ? "var(--col)" : 0 }}
      >
        <Content className="pt-[calc(var(--island-top)+var(--control-min-height)+2.125rem)] pb-[max(1.5rem,env(safe-area-inset-bottom))]" />
      </div>
    </>
  )
}

/**
 * THE PHONE: everything within the thumb's reach.
 *
 * Steward is used from an iPhone first, and the top corners are the two places on it a thumb
 * reaches worst. So the chrome is one dock floating above the home indicator, carrying the menu,
 * the name of the page, search and account, and the top of every page is the page. Opening the
 * navigation grows the same dock upward into a panel holding the list, its own row staying where it
 * was, so the finger that opened it closes it without moving.
 */
function PhoneFrame({ me, nav }: { me: Me; nav: Nav }) {
  const { shown, toggle, follow, close } = nav
  const page = usePageLabel()

  return (
    <>
      <div
        aria-hidden
        onClick={close}
        className={`fixed inset-0 z-30 bg-black/50 transition-opacity ${MOTION} ${shown ? "opacity-100" : "pointer-events-none opacity-0"}`}
      />

      <div className="fixed inset-x-(--gutter) bottom-[max(0.75rem,env(safe-area-inset-bottom))] z-40 flex flex-col overflow-hidden rounded-2xl border border-border bg-card/95 shadow-lg backdrop-blur">
        <div
          className={`grid transition-[grid-template-rows] ${MOTION} ${shown ? "grid-rows-[1fr]" : "grid-rows-[0fr]"}`}
          aria-hidden={!shown}
          inert={!shown}
        >
          <div className="min-h-0 overflow-hidden">
            <div className="max-h-[calc(var(--app-height)*0.66)] overflow-y-auto px-1 pt-3 pb-2">
              <NavList onFollow={follow} marker="surface" />
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1 p-1">
          <NavToggle shown={shown} onToggle={toggle} glyph="menu" />
          {/* The page's name is a second, larger target for the same toggle; the button is the one
              a keyboard or a screen reader uses, so this one is left out of both. */}
          <button
            type="button"
            onClick={toggle}
            tabIndex={-1}
            aria-hidden
            className="h-control min-w-0 flex-1 truncate px-1 text-left text-sm text-foreground"
          >
            {page ?? "Steward"}
          </button>
          <SearchButton />
          <UserMenu me={me} plain />
        </div>
      </div>

      <div className="flex min-w-0 flex-1 flex-col">
        <Content className="pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+1.5rem)] pb-[calc(max(0.75rem,env(safe-area-inset-bottom))+var(--control-min-height)+2.25rem)]" />
      </div>
    </>
  )
}

/** The label of the page being shown: its row in the navigation, which is what the dock says. */
function usePageLabel() {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const active = activeEntryId(pathname, NAVIGATION)
  for (const group of NAVIGATION) {
    // "Overview" is two rows; the second is Operations' own, and the dock says which one it is.
    for (const entry of group.entries)
      if (entry.id === active) return group.label && entry.label === "Overview" ? group.label : entry.label
  }
  return null
}

/** The one scrolling column. Each shape says how much room its chrome needs at the edges. */
function Content({ className }: { className: string }) {
  return (
    <ScrollArea className="min-h-0 flex-1">
      <main className={`mx-auto w-full max-w-[110rem] px-(--gutter) ${className}`}>
        <Outlet />
      </main>
    </ScrollArea>
  )
}
