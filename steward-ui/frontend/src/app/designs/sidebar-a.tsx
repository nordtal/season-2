import { CaretRightIcon } from "@phosphor-icons/react"
import { Fragment } from "react"

import { useCrumbs } from "@/app/breadcrumbs"
import { SearchButton } from "@/app/island"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { Brand, Content, GUTTER, MOTION, NavList, NavToggle, Scrim, useNav } from "@/app/designs/sidebar-parts"

/**
 * A - TODAY'S SHELL, PUT RIGHT.
 *
 * The same idea as the live frame: an island at the top left that becomes the head of the column.
 * What changes is only what was wrong with it. The island stands on the content's own gutter
 * instead of the window's edge, hugs what it carries instead of taking a fixed 20rem, and never
 * moves: opening the navigation slides the column in *under* it and the island merely drops its
 * border, so the toggle is where the finger already is. The phone does exactly the same thing with
 * a sheet, which is what makes the two devices one design rather than two. The right island is the
 * same shape at the same height, and the content keeps the same distance below both.
 */
export function FrameA({ me }: { me: Me }) {
  const { shown, isMobile, toggle, follow, close } = useNav()
  const crumbs = useCrumbs().slice(1)
  const pushed = shown && !isMobile

  return (
    <div className={`${GUTTER} [--col:15rem] flex h-(--app-height) w-full bg-background`}>
      <Scrim shown={shown && isMobile} onClose={close} className="z-30 md:hidden" />

      <aside
        aria-hidden={!shown}
        inert={!shown}
        className={`fixed inset-y-0 left-0 z-40 flex w-[min(var(--col),85vw)] flex-col border-r border-sidebar-border bg-sidebar transition-transform ${MOTION} ${shown ? "translate-x-0" : "-translate-x-full"} max-md:w-[min(18rem,85vw)]`}
      >
        <div className="min-h-0 flex-1 overflow-y-auto pt-[calc(var(--island-top)+var(--control-min-height)+1.75rem)] pr-3 pb-[max(1rem,env(safe-area-inset-bottom))] pl-[calc(var(--gutter)+5px)]">
          <NavList onFollow={follow} marker="blue" />
        </div>
      </aside>

      {/* The island. Fixed, never moves; only its surface comes and goes. */}
      <div
        className={`fixed top-(--island-top) left-(--gutter) z-50 flex max-w-[calc(100vw-2*var(--gutter)-7rem)] items-center gap-1 rounded-xl border p-1 transition-[background-color,border-color,box-shadow] ${MOTION} ${
          shown ? "border-transparent bg-transparent shadow-none" : "border-border bg-card shadow-sm"
        }`}
      >
        <NavToggle shown={shown} onToggle={toggle} glyph="panel" />
        <Brand onFollow={follow} className="pr-2" />
        {/* The trail, desktop only: on a phone the page's own title stands right under the island. */}
        <div
          className={`hidden min-w-0 items-center gap-1 overflow-hidden text-sm text-muted-foreground transition-[max-width,opacity] md:flex ${MOTION} ${
            shown ? "max-w-0 opacity-0" : "max-w-[32rem] pr-2 opacity-100"
          }`}
        >
          {crumbs.map((crumb, index) => (
            <Fragment key={crumb.href}>
              <CaretRightIcon className="size-3.5 shrink-0" aria-hidden />
              <a
                href={crumb.href}
                className={`truncate whitespace-nowrap hover:text-foreground ${index === crumbs.length - 1 ? "text-foreground" : ""}`}
              >
                {crumb.label}
              </a>
            </Fragment>
          ))}
        </div>
      </div>

      <div className="fixed top-(--island-top) right-(--gutter) z-20 flex items-center gap-1 rounded-xl border border-border bg-card p-1 shadow-sm">
        <SearchButton plain />
        <UserMenu me={me} plain />
      </div>

      <div
        className={`flex min-w-0 flex-1 flex-col transition-[margin] ${MOTION}`}
        style={{ marginLeft: pushed ? "var(--col)" : 0 }}
      >
        <Content className="pt-[calc(var(--island-top)+var(--control-min-height)+2.125rem)] pb-[max(1.5rem,env(safe-area-inset-bottom))]" />
      </div>
    </div>
  )
}
