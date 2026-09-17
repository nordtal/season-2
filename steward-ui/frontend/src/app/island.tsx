import { CaretDownIcon, CaretRightIcon, MagnifyingGlassIcon, SidebarIcon } from "@phosphor-icons/react"
import { Fragment } from "react"
import type { ComponentProps, ReactNode } from "react"

import type { Crumb } from "@/app/breadcrumbs"
import { StewardMark } from "@/app/steward-mark"
import { shortcutLabel } from "@/lib/keys"
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb"

/**
 * The island: the thing the header became (steward/89).
 *
 * Till, 2026-09-17, ordered one shape and left three questions open, and this file is the half that
 * is the same in all three answers - the pill at the top left carrying the navigation toggle and
 * the path of the page. The header is gone with its border, so nothing here may look like a bar:
 * the island is a small bordered surface lying on the background, and the row it sits in has no
 * border, no background and no height of its own beyond what the island needs.
 *
 * **Nothing inside the island is bordered** (Till's general rule, 2026-09-17). The island is
 * already a bordered surface, so every control in it is a ghost - a border inside a border is the
 * nesting the rule forbids, and it is what makes a control look like a second, smaller card.
 *
 * **390px is the width this was drawn for.** The island and the avatar sit on one line there, and
 * the only thing that may shrink is the path: the toggle is a tap target and keeps its size, and
 * the avatar is the only way to sign out and keeps its size. So the path truncates, and below `sm`
 * it is one crumb rather than a trail.
 */

/** A control inside the island. Ghost by definition - see the note above about borders. */
const ISLAND_CONTROL =
  "flex size-control shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors duration-150 ease-out hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none active:bg-secondary"

/** The bordered surface itself, at the size a finger expects. */
const ISLAND_SURFACE =
  "flex min-w-0 items-center gap-0.5 rounded-lg border border-border bg-card p-1"

export function IslandSurface({
  children,
  floating,
  className,
}: {
  children: ReactNode
  /** Lifted off the page, for the shell where the content scrolls underneath it. */
  floating?: boolean
  className?: string
}) {
  return (
    <div className={`${ISLAND_SURFACE} ${floating ? "shadow-sm" : ""} ${className ?? ""}`}>
      {children}
    </div>
  )
}

/**
 * The mark and the word, standing in for "Overview" as the first crumb (steward/89, shells d/e/f/g).
 *
 * Till, 2026-09-17: the island's first element is always the Nordtal mark and the word "Steward" -
 * not "Nordtal Steward", the word alone - and it leads where Overview led. It replaces the crumb
 * rather than sitting beside it, so `Crumbs` below draws this in place of `crumbs[0].label` instead
 * of adding a fifth element to a row that is already tight at 390px.
 */
function BrandLabel() {
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      <StewardMark className="size-4 shrink-0" />
      <span className="truncate">Steward</span>
    </span>
  )
}

/**
 * A corner every fixed control in shells d/f/g anchors to, so the four addresses agree on the
 * offset instead of each carrying its own copy of the safe-area and blur-clearance arithmetic.
 *
 * `pointer-events-none` on the row and `pointer-events-auto` on whatever is put inside it: the
 * strip spans the header the way shell B's floating island already did, and must not steal clicks
 * from the page over the space it does not actually draw anything in.
 */
export function FixedCorner({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={`pointer-events-none fixed inset-x-0 top-0 z-30 flex items-start px-4 pt-[calc(env(safe-area-inset-top)+var(--blur-clearance)+0.75rem)] md:px-6 ${className ?? ""}`}
    >
      {children}
    </div>
  )
}

/**
 * The navigation toggle.
 *
 * `expanded` is drawn rather than hidden: the panel icon keeps its shape and the state is in the
 * chevron beside it in the shell that wants one, so the button never changes size and never moves
 * the path beside it by a pixel as the sidebar opens.
 */
export function SidebarToggle({
  expanded,
  onToggle,
  rotate,
  label = "Navigation",
  className,
}: {
  expanded: boolean
  onToggle: () => void
  /** Turn the icon over when the navigation is open, for the shell whose island stays put. */
  rotate?: boolean
  label?: string
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={label}
      aria-expanded={expanded}
      className={`${ISLAND_CONTROL} ${expanded ? "text-foreground" : ""} ${className ?? ""}`}
    >
      <SidebarIcon
        className={`size-4 transition-transform duration-200 ease-out ${rotate && expanded ? "rotate-180" : ""}`}
        aria-hidden
      />
    </button>
  )
}

/**
 * The path of the page, as links.
 *
 * ON A PHONE ONLY THE LAST CRUMB IS SHOWN, which is the header's own rule inherited: the list
 * wraps, and "Overview > Configuration > steward-worker > steward-worker.yml" is four lines inside
 * a pill that is one line tall. `flex-nowrap` is the belt to that brace - a two-crumb path that
 * still does not fit truncates rather than growing the island.
 */
export function Crumbs({
  crumbs,
  linked = true,
  brand = false,
}: {
  crumbs: Crumb[]
  linked?: boolean
  /**
   * Draw `crumbs[0]` as the Nordtal mark and the word "Steward" instead of its label (steward/89,
   * shells d/e/f/g). It replaces "Overview", so it is exempt from the phone rule below that hides
   * every ancestor but the last - the order is explicit that this one crumb never disappears.
   */
  brand?: boolean
}) {
  // Inside a button the trail is text, and it is written out rather than borrowed: the breadcrumb
  // component is a `nav` around an ordered list, and a landmark inside a button is neither a
  // landmark nor a button.
  if (!linked) {
    return (
      <span className="flex min-w-0 items-center gap-1 text-sm">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1
          const isBrand = brand && index === 0
          const label = isBrand ? <BrandLabel /> : crumb.label
          return last ? (
            <span key={crumb.href} className="min-w-0 truncate text-foreground">
              {label}
            </span>
          ) : (
            <span
              key={crumb.href}
              className={`shrink-0 items-center gap-1 text-muted-foreground ${
                isBrand ? "flex" : "hidden sm:flex"
              }`}
            >
              <span className="truncate">{label}</span>
              <CaretRightIcon className="size-3.5" aria-hidden />
            </span>
          )
        })}
      </span>
    )
  }

  return (
    <Breadcrumb className="min-w-0">
      <BreadcrumbList className="flex-nowrap gap-1 text-sm sm:gap-1.5">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1
          const isBrand = brand && index === 0
          const label = isBrand ? <BrandLabel /> : crumb.label
          // The separator is a sibling of the item, never a child of it: both are `li`, and the
          // old header nested them - which is invalid, and which a rendering test says out loud.
          //
          // The ancestors are what gives way when the trail does not fit, and the two classes below
          // are the whole of that rule. `min-w-0` lets an item shrink at all - without it a flex
          // item cannot go below its content width, so in a 256px sidebar head the entire
          // shortfall landed on the only item that could, which was the last: the current page
          // was the one crumb that disappeared, leaving a trail ending in a separator pointing at
          // nothing. The lopsided shrink factor is then what puts the order right - a flex item
          // gives up space in proportion to that factor, so the ancestors are down to their
          // ellipses long before the page name loses a letter, and nothing ever overflows the
          // column the way a `shrink-0` last crumb would. Measured at 1440px on /designs/network,
          // which is three segments deep; so is /services/smp.
          //
          // The brand crumb (steward/89, 2026-09-17) is the one exception to "ancestors give way
          // first": it carries the mark and the bare word "Steward", and a shell that lets that
          // shrink to an ellipsis before an in-between segment does is un-branding itself under
          // its own name. It shares the page name's own gentle `shrink` rather than the
          // ancestors' `shrink-[999]` - the segments between them absorb the shortfall first and
          // go to their ellipsis long before either end does.
          //
          // `shrink-0` was tried first and made it worse: a completely rigid brand crumb, once the
          // in-between ancestors had already given up everything they had, pushed the *entire*
          // remaining overflow onto the one other flexible item left standing - the page name -
          // which is the exact disappearance this whole rule exists to prevent. A 13rem sidebar
          // head with "Steward", one ancestor and a page name is tight enough that this actually
          // happened: `/services/smp` folded into shell d's sidebar head showed "Steward > >"
          // with both "Services" and "smp" gone. Splitting the modest, low-priority shrink between
          // brand and page name is what keeps both readable as an ellipsis instead.
          return (
            <Fragment key={crumb.href}>
              <BreadcrumbItem
                className={
                  last || isBrand
                    ? "min-w-0 shrink"
                    : "min-w-0 shrink-[999] max-sm:hidden"
                }
              >
                {last ? (
                  <BreadcrumbPage className="truncate">{label}</BreadcrumbPage>
                ) : (
                  <BreadcrumbLink
                    href={crumb.href}
                    className="truncate transition-colors duration-150 ease-out hover:text-primary"
                  >
                    {label}
                  </BreadcrumbLink>
                )}
              </BreadcrumbItem>
              {last ? null : (
                <BreadcrumbSeparator className={isBrand ? "" : "max-sm:hidden"} />
              )}
            </Fragment>
          )
        })}
      </BreadcrumbList>
    </Breadcrumb>
  )
}

/**
 * Toggle and path in one bordered pill: the island as Till described it.
 *
 * `trailing` is what a shell puts at the far end of it - the search in one of the three, nothing in
 * the others.
 */
export function PathIsland({
  crumbs,
  expanded,
  onToggle,
  rotate,
  trailing,
  floating,
  brand,
  hideToggle,
  className,
}: {
  crumbs: Crumb[]
  expanded: boolean
  onToggle: () => void
  rotate?: boolean
  trailing?: ReactNode
  floating?: boolean
  /** The first crumb is the Nordtal mark and "Steward", not "Overview" - see {@link Crumbs}. */
  brand?: boolean
  /**
   * Draw a same-sized blank instead of the toggle button (shells d/f/g): the toggle is answered
   * once, fixed to the corner, and a second live button here would be a second way to do the same
   * thing at a different address - the blank keeps the path where it would sit if the toggle were
   * drawn, so nothing shifts when a shell switches between having one here and not.
   */
  hideToggle?: boolean
  className?: string
}) {
  return (
    <IslandSurface floating={floating} className={className}>
      {hideToggle ? (
        <span className="size-control shrink-0" aria-hidden />
      ) : (
        <SidebarToggle expanded={expanded} onToggle={onToggle} rotate={rotate} />
      )}
      <div className="min-w-0 flex-1 px-1">
        <Crumbs crumbs={crumbs} brand={brand} />
      </div>
      {trailing}
    </IslandSurface>
  )
}

/**
 * The island as one button, for the shell where it opens a menu rather than a column.
 *
 * Everything in it is the target - icon, path and chevron - which on a phone is a target about
 * 300px wide instead of a 44px square. That is the whole point of that shell, so the path is drawn
 * as text here and not as links: a link inside a button is neither.
 */
export function MenuIsland({
  crumbs,
  expanded,
  floating,
  className,
  ...rest
}: {
  crumbs: Crumb[]
  expanded: boolean
  floating?: boolean
  className?: string
} & ComponentProps<"button">) {
  return (
    <button
      type="button"
      aria-label="Navigation"
      aria-expanded={expanded}
      className={`${ISLAND_SURFACE} ${floating ? "shadow-sm" : ""} w-full gap-1 pr-2 text-left transition-colors duration-150 ease-out hover:border-input focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none ${className ?? ""}`}
      // Last, so that a popover trigger wrapping this keeps its own click handler, its ref and its
      // `aria-expanded`. This component is the shape; the menu that owns it is the behaviour.
      {...rest}
    >
      <span className={`${ISLAND_CONTROL} pointer-events-none`}>
        <SidebarIcon className="size-4" aria-hidden />
      </span>
      <span className="min-w-0 flex-1">
        <Crumbs crumbs={crumbs} linked={false} />
      </span>
      <CaretDownIcon
        className={`size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out ${expanded ? "rotate-180" : ""}`}
        aria-hidden
      />
    </button>
  )
}

/**
 * Looks like a search field and is a button, because it is one: it does nothing but open the
 * palette, and a real input here would be a second place to type the same query.
 *
 * **It must never disappear.** `⌘K` does not exist on a phone, so without something to tap the
 * command palette is unreachable there - which is why every one of the three shells has to say
 * where this went, and each says so in its own comment.
 *
 * `plain` is the form for the inside of the island, where a border would be a border inside a
 * border. The bordered form is for a search that stands on the background by itself.
 *
 * The key it prints is the key the reader has: `⌘K` on a Mac, `Ctrl+K` on everything else.
 */
export function openCommandPalette() {
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "k", ctrlKey: true, bubbles: true }))
}

export function SearchButton({ plain }: { plain?: boolean }) {
  return (
    <button
      type="button"
      onClick={openCommandPalette}
      aria-label="Search pages"
      className={
        plain
          ? ISLAND_CONTROL
          : "flex size-control shrink-0 items-center justify-center rounded-md border border-border bg-card text-muted-foreground transition-colors duration-150 ease-out hover:border-input hover:text-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none active:bg-secondary"
      }
    >
      <MagnifyingGlassIcon className="size-4" aria-hidden />
    </button>
  )
}

/** The wide form, for a menu that has room for the words and for the shortcut. */
export function SearchRow({ onDone }: { onDone?: () => void }) {
  return (
    <button
      type="button"
      onClick={() => {
        onDone?.()
        openCommandPalette()
      }}
      className="flex min-h-control w-full items-center gap-2 rounded-md px-2 text-sm text-muted-foreground transition-colors duration-150 ease-out hover:bg-secondary hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <MagnifyingGlassIcon className="size-4 shrink-0" aria-hidden />
      <span>Search pages…</span>
      <kbd className="ml-auto hidden rounded-sm bg-secondary px-1.5 py-0.5 font-mono text-[0.6875rem] text-foreground sm:inline">
        {shortcutLabel("K")}
      </kbd>
    </button>
  )
}
