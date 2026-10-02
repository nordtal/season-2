import { Outlet, useRouterState } from "@tanstack/react-router"
import { useEffect } from "react"

import { NavList, activeEntryId } from "@/app/app-sidebar"
import { useCrumbs } from "@/app/breadcrumbs"
import { Brand, MOTION, NavToggle, SearchButton, Trail } from "@/app/island"
import { useNavigation } from "@/app/navigation"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { ScrollArea } from "@/components/ui/scroll-area"
import { useSidebar } from "@/components/ui/sidebar"

/**
 * The signed in frame: {@link DesktopFrame} from 640px up, {@link PhoneFrame} below.
 *
 * Everything aligns on `--gutter`, and only {@link Content} scrolls, never the document.
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
    if (!shown) return undefined
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
 * The desktop and tablet frame: a fixed island over a column that slides in beneath it.
 *
 * Opening moves nothing in the island; it drops its surface and path, becoming the column's head.
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
        className={`fixed top-(--island-top) left-(--gutter) z-50 gap-2 flex max-w-[calc(100vw-2*var(--gutter)-7rem)] items-center rounded-xl border p-1 transition-[background-color,border-color,box-shadow] ${MOTION} ${
          shown ? "border-transparent bg-transparent shadow-none" : "border-border bg-card shadow-sm"
        }`}
      >
        <NavToggle shown={shown} onToggle={toggle} glyph="panel" />
        <Brand />
        {/* While the column is open the list says where you are, so the path folds away. */}
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

/** The phone frame: one dock above the home indicator that grows upward into the navigation. */
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
          {/* A larger target for the same toggle, left out of keyboard and screen reader order. */}
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
  const groups = useNavigation()
  const active = activeEntryId(pathname, groups)
  for (const group of groups) {
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
