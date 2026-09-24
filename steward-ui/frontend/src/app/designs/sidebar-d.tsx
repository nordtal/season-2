import { SearchButton } from "@/app/island"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { Brand, Content, GUTTER, MOTION, NavList, NavToggle, Scrim, useNav } from "@/app/designs/sidebar-parts"

/**
 * D - ONE CARD THAT UNFOLDS.
 *
 * There is exactly one surface for all of the chrome: a card carrying the menu, the mark, search
 * and account. Collapsed it is only its own first row. Opening the navigation unfolds the same card
 * downward, the row staying where it is, and the list appears beneath it - so there is no second
 * island to match, no column edge, and no moment where one thing is swapped for another.
 *
 * On a phone the card spans the width and unfolds over a dimmed page. On a desktop it keeps the
 * width of the column it becomes, floats one gutter away from every edge, and the content steps
 * aside for it. The selected place is blue text, as it is today.
 */
export function FrameD({ me }: { me: Me }) {
  const { shown, isMobile, toggle, follow, close } = useNav()
  const pushed = shown && !isMobile

  return (
    <div className={`${GUTTER} [--col:16rem] flex h-(--app-height) w-full bg-background`}>
      <Scrim shown={shown && isMobile} onClose={close} className="z-30 md:hidden" />

      <div
        className={`fixed top-(--island-top) left-(--gutter) z-40 flex flex-col overflow-hidden rounded-2xl border border-border bg-card transition-shadow max-md:right-(--gutter) md:w-(--col) ${MOTION} ${shown ? "shadow-lg" : "shadow-sm"}`}
      >
        <div className="flex items-center gap-1 p-1">
          <NavToggle shown={shown} onToggle={toggle} glyph="panel" />
          <Brand onFollow={follow} className="pr-1" />
          <div className="flex-1" />
          <SearchButton plain />
          <UserMenu me={me} plain />
        </div>
        <div
          className={`grid transition-[grid-template-rows] ${MOTION} ${shown ? "grid-rows-[1fr]" : "grid-rows-[0fr]"}`}
          inert={!shown}
        >
          <div className="min-h-0 overflow-hidden">
            <div className="max-h-[calc(var(--app-height)-var(--island-top)-var(--control-min-height)-10px-var(--gutter))] overflow-y-auto px-1 pt-2 pb-2">
              <NavList onFollow={follow} marker="blue" />
            </div>
          </div>
        </div>
      </div>

      <div
        className={`flex min-w-0 flex-1 flex-col transition-[margin] ${MOTION}`}
        style={{ marginLeft: pushed ? "calc(var(--col) + var(--gutter))" : 0 }}
      >
        <Content className="pt-[calc(var(--island-top)+var(--control-min-height)+2.125rem)] pb-[max(1.5rem,env(safe-area-inset-bottom))]" />
      </div>
    </div>
  )
}
