import { SearchButton } from "@/app/island"
import { UserMenu } from "@/app/user-menu"
import type { Me } from "@/lib/api"
import { Brand, Content, GUTTER, MOTION, NavList, NavToggle, useNav } from "@/app/designs/sidebar-parts"

/**
 * B - NO SURFACES AT ALL.
 *
 * Nothing in the chrome has a border or a background of its own. Three glyphs stand on the page's
 * own background - the menu at the left, search and account at the right - with their glyphs on the
 * content's gutter, so the menu icon is exactly above the first letter of the page title. A soft
 * fade of the background colour behind them is what keeps scrolling text from running through
 * them; it is not a bar, it has no edge.
 *
 * Opened on a phone the navigation takes the whole screen, under the same three glyphs, and the
 * menu glyph turns into a cross in place. On a desktop the same list is a column of the page's own
 * colour with no rule beside it: the whitespace is the separation. The selected place is marked by
 * brightness alone - everything else is muted - which is the quietest marker of the four.
 */
export function FrameB({ me }: { me: Me }) {
  const { shown, isMobile, toggle, follow } = useNav()
  const pushed = shown && !isMobile
  // The glyph, not the box, stands on the gutter: the box is `size-control` and the glyph is
  // 18px, so the box sits half the difference further out.
  const optical = "calc(var(--gutter) - (var(--control-min-height) - 1.125rem) / 2)"

  return (
    <div className={`${GUTTER} [--col:15rem] flex h-(--app-height) w-full bg-background`}>
      <div
        aria-hidden
        className="pointer-events-none fixed inset-x-0 top-0 z-20 h-[calc(var(--island-top)+var(--control-min-height)+1rem)] bg-linear-to-b from-background from-60% to-transparent"
      />

      <aside
        aria-hidden={!shown}
        inert={!shown}
        className={`fixed left-0 z-40 bg-background transition-[opacity,translate] ${MOTION} max-md:inset-0 md:inset-y-0 md:w-(--col) ${
          shown
            ? "translate-x-0 translate-y-0 opacity-100"
            : "pointer-events-none opacity-0 max-md:-translate-y-2 md:-translate-x-4"
        }`}
      >
        <div
          className="h-full overflow-y-auto pt-[calc(var(--island-top)+var(--control-min-height)+1.25rem)] pb-[max(1.5rem,env(safe-area-inset-bottom))] md:pr-2"
          style={{ paddingLeft: "calc(var(--gutter) - (var(--control-min-height) - 1rem) / 2)" }}
        >
          <NavList onFollow={follow} marker="bright" className="max-md:pr-(--gutter)" />
        </div>
      </aside>

      <div className="fixed top-(--island-top) z-50 flex items-center gap-1" style={{ left: optical }}>
        <NavToggle shown={shown} onToggle={toggle} glyph="menu" />
        <div
          className={`transition-[opacity,translate] ${MOTION} ${shown ? "translate-x-0 opacity-100" : "pointer-events-none -translate-x-1 opacity-0"}`}
          inert={!shown}
        >
          <Brand onFollow={follow} />
        </div>
      </div>

      <div className="fixed top-(--island-top) z-50 flex items-center" style={{ right: optical }}>
        <SearchButton plain />
        <UserMenu me={me} plain />
      </div>

      <div
        className={`flex min-w-0 flex-1 flex-col transition-[margin] ${MOTION}`}
        style={{ marginLeft: pushed ? "var(--col)" : 0 }}
      >
        <Content className="pt-[calc(var(--island-top)+var(--control-min-height)+1.25rem)] pb-[max(1.5rem,env(safe-area-inset-bottom))]" />
      </div>
    </div>
  )
}
