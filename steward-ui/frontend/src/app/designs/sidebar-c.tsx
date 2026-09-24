import { SearchButton } from "@/app/island"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import {
  Brand,
  Content,
  GUTTER,
  MOTION,
  NavList,
  NavToggle,
  Scrim,
  useNav,
  usePageLabel,
} from "@/app/designs/sidebar-parts"

/**
 * C - EVERYTHING WITHIN THE THUMB'S REACH.
 *
 * Steward is used from an iPhone first, and the top corners are the two places on it a thumb
 * reaches worst. So the chrome moves to the bottom: one dock, floating above the home indicator,
 * carrying the menu, the name of the page, search and account. The top of every page is the page.
 *
 * On a phone the dock is also the navigation: it grows upward into a panel holding the list, with
 * its own row staying where it was, so the finger that opened it closes it without moving. On a
 * desktop the list is a column instead, and the dock is that column's foot - at exactly the spot it
 * floats at when the column is closed, so the toggle never moves there either. The selected place
 * is a quiet surface rather than a colour.
 */
export function FrameC({ me }: { me: Me }) {
  const { shown, isMobile, toggle, follow, close } = useNav()
  const page = usePageLabel()
  const pushed = shown && !isMobile
  const dockBottom = "max(0.75rem,env(safe-area-inset-bottom))"

  return (
    <div className={`${GUTTER} [--col:16rem] flex h-(--app-height) w-full bg-background`}>
      <Scrim shown={shown && isMobile} onClose={close} className="z-30 md:hidden" />

      {/* The desktop column. On a phone the list lives inside the dock instead. */}
      <aside
        aria-hidden={!shown || isMobile}
        inert={!shown || isMobile}
        className={`fixed inset-y-0 left-0 z-30 hidden w-(--col) flex-col border-r border-sidebar-border bg-sidebar transition-transform md:flex ${MOTION} ${shown ? "translate-x-0" : "-translate-x-full"}`}
      >
        <div className="flex h-[calc(var(--island-top)+var(--control-min-height))] items-end pl-[calc(var(--gutter)+5px+(var(--control-min-height)-1.25rem)/2)]">
          <Brand className="h-control" />
        </div>
        {/* The list ends above the dock rather than running on underneath it. */}
        <div className="mb-[calc(max(0.75rem,env(safe-area-inset-bottom))+var(--control-min-height)+10px+0.75rem)] min-h-0 flex-1 overflow-y-auto pt-5 pr-3 pb-2 pl-[calc(var(--gutter)+5px)]">
          <NavList onFollow={follow} marker="surface" />
        </div>
      </aside>

      <div
        className="fixed inset-x-(--gutter) z-40 flex flex-col overflow-hidden rounded-2xl border border-border bg-card/95 shadow-lg backdrop-blur md:right-auto md:w-[calc(var(--col)-2*var(--gutter))]"
        style={{ bottom: dockBottom }}
      >
        {/* The phone's list: the dock grows upward to hold it. */}
        <div
          className={`grid transition-[grid-template-rows] md:hidden ${MOTION} ${shown && isMobile ? "grid-rows-[1fr]" : "grid-rows-[0fr]"}`}
          inert={!(shown && isMobile)}
        >
          <div className="min-h-0 overflow-hidden">
            <div className="max-h-[calc(var(--app-height)*0.66)] overflow-y-auto px-1 pt-3 pb-2">
              <NavList onFollow={follow} marker="surface" />
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1 p-1">
          <NavToggle shown={shown} onToggle={toggle} glyph="menu" />
          <button
            type="button"
            onClick={toggle}
            tabIndex={-1}
            className="min-w-0 flex-1 truncate px-1 text-left text-sm text-foreground"
          >
            {page ?? "Steward"}
          </button>
          <SearchButton plain />
          <UserMenu me={me} plain align="end" />
        </div>
      </div>

      <div
        className={`flex min-w-0 flex-1 flex-col transition-[margin] ${MOTION}`}
        style={{ marginLeft: pushed ? "var(--col)" : 0 }}
      >
        <Content className="pt-[calc(env(safe-area-inset-top)+1.5rem)] pb-[calc(max(0.75rem,env(safe-area-inset-bottom))+var(--control-min-height)+2.25rem)]" />
      </div>
    </div>
  )
}
