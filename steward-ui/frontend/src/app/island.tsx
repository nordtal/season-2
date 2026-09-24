import { Link } from "@tanstack/react-router"
import { CaretRightIcon, ListIcon, MagnifyingGlassIcon, SidebarIcon, XIcon } from "@phosphor-icons/react"
import { Fragment } from "react"

import type { Crumb } from "@/app/breadcrumbs"
import { StewardMark } from "@/app/steward-mark"

/**
 * The pieces the frame's chrome is built from: the toggle, the mark, the trail and the search.
 *
 * **Nothing in here has a border of its own** (Till's general rule, 2026-09-17). Every one of them
 * stands inside a surface that already has one - the island, the right-hand island, the phone's
 * dock - and a border inside a border is the nesting that rule forbids.
 */

/** A ghost control, the one shape every button in the chrome has. */
export const CONTROL =
  "flex size-control shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors duration-150 ease-out hover:bg-secondary/60 hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"

/** The one motion of the frame: the column, the island's surface and the dock all move with it. */
export const MOTION = "duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:duration-0"

/**
 * The navigation toggle. `panel` is the desktop's drawing and never changes; `menu` is the phone's,
 * and turns into a cross in place while the navigation is open, because there the same button is
 * the only way to close it again.
 */
export function NavToggle({
  shown,
  onToggle,
  glyph,
}: {
  shown: boolean
  onToggle: () => void
  glyph: "panel" | "menu"
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label="Navigation"
      aria-expanded={shown}
      className={`${CONTROL} ${shown ? "text-foreground" : ""}`}
    >
      {glyph === "panel" ? (
        <SidebarIcon className="size-4" aria-hidden />
      ) : (
        <span className="relative size-[1.125rem]" aria-hidden>
          <ListIcon
            className={`absolute inset-0 size-full transition-[opacity,rotate] ${MOTION} ${shown ? "rotate-90 opacity-0" : "opacity-100"}`}
          />
          <XIcon
            className={`absolute inset-0 size-full transition-[opacity,rotate] ${MOTION} ${shown ? "opacity-100" : "-rotate-90 opacity-0"}`}
          />
        </span>
      )}
    </button>
  )
}

/**
 * The mark and the word "Steward" - the word alone, not "Nordtal Steward" (Till, 2026-09-17) - and
 * it leads where Overview leads.
 *
 * It has no padding on its left on purpose. It stands directly after the toggle, whose box ends
 * exactly where every row's label begins, so the mark starts on the same line as "Overview" below
 * it rather than a few pixels beside it.
 */
export function Brand({ onFollow }: { onFollow?: () => void }) {
  return (
    <Link
      to="/"
      onClick={onFollow}
      className="flex h-control min-w-0 shrink-0 items-center gap-2 rounded-md pr-2 text-sm font-semibold tracking-tight transition-colors duration-150 ease-out hover:text-primary focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <StewardMark className="size-5 shrink-0" />
      <span className="truncate">Steward</span>
    </Link>
  )
}

/**
 * The path of the page after the mark, which stands in for its first crumb.
 *
 * The ancestors give way before the page name does: `shrink-[999]` against a plain `shrink` puts
 * the whole shortfall on them first, so a trail that does not fit ends in "… > smp" and never in a
 * separator pointing at nothing.
 */
export function Trail({ crumbs }: { crumbs: Crumb[] }) {
  return (
    <span className="flex min-w-0 items-center gap-1 text-sm text-muted-foreground">
      {crumbs.map((crumb, index) => {
        const last = index === crumbs.length - 1
        return (
          <Fragment key={crumb.href}>
            <CaretRightIcon className="size-3.5 shrink-0" aria-hidden />
            <Link
              to={crumb.href as never}
              aria-current={last ? "page" : undefined}
              className={`truncate whitespace-nowrap rounded-sm transition-colors duration-150 ease-out hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none ${
                last ? "min-w-0 shrink text-foreground" : "min-w-0 shrink-[999]"
              }`}
            >
              {crumb.label}
            </Link>
          </Fragment>
        )
      })}
    </span>
  )
}

/**
 * Opens the command palette. The palette listens for the key, so a tap sends the key.
 *
 * **The search button must never disappear.** `⌘K` does not exist on a phone, so without something
 * to tap the command palette is unreachable there.
 */
export function openCommandPalette() {
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true }))
}

/** Looks like a place to type and is a button, because a real input here would be a second one. */
export function SearchButton() {
  return (
    <button type="button" onClick={openCommandPalette} aria-label="Search pages" className={CONTROL}>
      <MagnifyingGlassIcon className="size-4" aria-hidden />
    </button>
  )
}
