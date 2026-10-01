import { Link } from "@tanstack/react-router"
import { CaretRightIcon, ListIcon, MagnifyingGlassIcon, SidebarIcon, XIcon } from "@phosphor-icons/react"
import { Fragment } from "react"

import type { Crumb } from "@/app/breadcrumbs"
import { StewardMark } from "@/app/steward-mark"

/** The chrome's pieces: the toggle, the mark, the trail and the search, none with a border of its own. */

/** A ghost control, the one shape every button in the chrome has. */
export const CONTROL =
  "flex size-control shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors duration-150 ease-out hover:bg-secondary/60 hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"

/** The one motion of the frame, shared by the column, the island's surface and the dock. */
export const MOTION = "duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:duration-0"

/** The navigation toggle; on a phone it turns into a cross, since there it is the only way to close. */
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
 * The mark and the word "Steward", leading to Overview.
 *
 * No left padding, so the mark starts on the line where every label starts.
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

/** The page's path after the mark, the ancestors shrinking first so a tight trail still ends on the page. */
export function Trail({ crumbs }: { crumbs: Crumb[] }) {
  return (
    <span className="flex min-w-0 items-center gap-1 text-sm text-muted-foreground">
      {crumbs.map((crumb, index) => {
        const last = index === crumbs.length - 1
        return (
          <Fragment key={crumb.href}>
            <CaretRightIcon className="size-3.5 shrink-0" aria-hidden />
            <Link
              to={crumb.href}
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

/** Opens the command palette by sending its key, which a phone cannot type. */
export function openCommandPalette() {
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true }))
}

/** A button that looks like a place to type, since a real input would be a second one. */
export function SearchButton() {
  return (
    <button type="button" onClick={openCommandPalette} aria-label="Search pages" className={CONTROL}>
      <MagnifyingGlassIcon className="size-4" aria-hidden />
    </button>
  )
}
