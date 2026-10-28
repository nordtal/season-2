import { Outlet } from "@tanstack/react-router"
import { useState } from "react"
import type { CSSProperties } from "react"

import { NavList } from "@/app/app-sidebar"
import { useCrumbs, useDocumentTitle } from "@/app/breadcrumbs"
import { Brand, BrandFace, MOTION, NavToggle, PhoneBarSlot, SearchButton, Trail } from "@/app/island"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { ScrollArea } from "@/components/ui/scroll-area"
import { useSidebar } from "@/components/ui/sidebar"

/**
 * The chrome aligns on `--gutter` and the page on `--page-gutter`, which is further in on a phone. The bottom chrome is placed against this frame, because iOS can leave a fixed bottom element too high.
 */
export function AppFrame({ me }: { me: Me }) {
  const nav = useNav()
  useDocumentTitle(useCrumbs())
  return (
    <div className="relative flex h-(--app-height) w-full bg-background [--col:17rem] [--gutter:1rem] [--page-gutter:var(--gutter)] md:[--gutter:1.5rem]">
      {nav.isMobile ? <PhoneFrame me={me} nav={nav} /> : <DesktopFrame me={me} nav={nav} />}
    </div>
  )
}

type Nav = ReturnType<typeof useNav>

function useNav() {
  const { open, openMobile, isMobile, setOpenMobile, toggleSidebar } = useSidebar()
  const shown = isMobile ? openMobile : open

  return {
    shown,
    isMobile,
    toggle: toggleSidebar,
    follow: () => {
      if (isMobile) setOpenMobile(false)
    },
    close: () => setOpenMobile(false),
  }
}

/**
 * The island drops its surface and path and becomes the head of the column, search and account included.
 * An empty path is not drawn, because its padding and gap would widen the space before search on every toggle.
 */
function DesktopFrame({ me, nav }: { me: Me; nav: Nav }) {
  const { shown, toggle } = nav
  const crumbs = useCrumbs().slice(1)

  return (
    <>
      <aside
        aria-hidden={!shown}
        inert={!shown}
        className={`absolute inset-y-0 left-0 z-40 flex w-(--col) flex-col border-r border-sidebar-border bg-sidebar transition-transform ${MOTION} ${shown ? "translate-x-0" : "-translate-x-full"}`}
      >
        <ScrollArea className="min-h-0 flex-1">
          <div className="pt-[calc(var(--island-top)+(--spacing(1))+1px+var(--control-min-height)+(--spacing(4)))] pr-3 pb-[max(--spacing(4),env(safe-area-inset-bottom))] pl-[calc(var(--gutter)+(--spacing(1))+1px)]">
            <NavList marker="text" />
          </div>
        </ScrollArea>
      </aside>

      <div
        className={`fixed top-(--island-top) left-(--gutter) z-50 gap-2 flex max-w-[calc(100vw-2*var(--gutter))] items-center rounded-xl border p-1 transition-[background-color,border-color,box-shadow] ${MOTION} ${
          shown ? "border-transparent bg-transparent shadow-none" : "border-border bg-card shadow-sm"
        }`}
      >
        <NavToggle shown={shown} onToggle={toggle} glyph="panel" />
        <Brand version={shown} />
        {crumbs.length > 0 ? (
          <div
            aria-hidden={shown}
            inert={shown}
            data-trail
            className={`flex min-w-0 overflow-hidden transition-[max-width,opacity] ${MOTION} ${
              shown ? "max-w-0 opacity-0" : "max-w-lg pr-2 opacity-100"
            }`}
          >
            <Trail crumbs={crumbs} />
          </div>
        ) : null}
        <div className="flex shrink-0 items-center gap-1">
          <SearchButton />
          <UserMenu me={me} align="start" plain />
        </div>
      </div>

      {/* `--content-top` is the room the closed column's island takes above the page, and zero beside the open column. */}
      <div
        className={`flex min-w-0 flex-1 flex-col transition-[margin] ${MOTION}`}
        style={
          {
            marginLeft: shown ? "var(--col)" : 0,
            "--content-top": shown ? "0px" : "calc(var(--island-top) + var(--control-min-height) + 10px)",
          } as CSSProperties
        }
      >
        <Content
          className={`pt-[calc(var(--content-top)+(--spacing(6)))] transition-[padding-top] ${MOTION} [--content-bottom:max(1.5rem,env(safe-area-inset-bottom))]`}
        />
      </div>
    </>
  )
}

function PhoneFrame({ me, nav }: { me: Me; nav: Nav }) {
  const { shown, toggle, follow, close } = nav
  const [slot, setSlot] = useState<HTMLElement | null>(null)

  return (
    <>
      <div
        aria-hidden
        onClick={close}
        className={`absolute inset-0 z-30 bg-overlay/50 transition-opacity ${MOTION} ${shown ? "opacity-100" : "pointer-events-none opacity-0"}`}
      />

      <div className="absolute inset-x-(--gutter) bottom-[max(--spacing(3),env(safe-area-inset-bottom))] z-40 flex flex-col overflow-hidden rounded-2xl border border-border bg-card/95 shadow-lg backdrop-blur">
        <div
          className={`grid transition-[grid-template-rows] ${MOTION} ${shown ? "grid-rows-[1fr]" : "grid-rows-[0fr]"}`}
          aria-hidden={!shown}
          inert={!shown}
        >
          <div className="min-h-0 overflow-hidden">
            <div className="max-h-[calc(var(--app-height)*0.66)] overflow-y-auto px-3 pt-3 pb-2">
              <NavList onFollow={follow} marker="surface" />
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1 p-2">
          <NavToggle shown={shown} onToggle={toggle} glyph="menu" />
          {/* A larger target for the same toggle. Keyboard and screen reader navigation skip it. */}
          <button
            type="button"
            onClick={toggle}
            tabIndex={-1}
            aria-hidden
            className="flex h-control min-w-0 flex-1 items-center gap-2 px-1 text-left text-sm font-semibold tracking-tight text-foreground"
          >
            <BrandFace version={shown} />
          </button>
          <span ref={setSlot} className="contents" />
          <SearchButton />
          <UserMenu me={me} plain />
        </div>
      </div>

      <div className="flex min-w-0 flex-1 flex-col">
        <PhoneBarSlot value={slot}>
          <Content className="pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+(--spacing(6)))] [--page-gutter:1.5rem] [--content-bottom:calc(max(0.75rem,env(safe-area-inset-bottom))+var(--control-min-height)+2.75rem)]" />
        </PhoneBarSlot>
      </div>
    </>
  )
}

/**
 * Each frame shape sets the room its chrome needs at the edges. `--content-bottom` is the bottom room, which a row pinned to the bottom also keeps clear.
 */
function Content({ className }: { className: string }) {
  return (
    <ScrollArea className="min-h-0 flex-1">
      <main className={`mx-auto w-full max-w-page px-(--page-gutter) pb-(--content-bottom) ${className}`}>
        <Outlet />
      </main>
    </ScrollArea>
  )
}
