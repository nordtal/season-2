import { CaretRightIcon, MagnifyingGlassIcon, SidebarIcon } from "@phosphor-icons/react"
import { Fragment } from "react"

import type { Crumb } from "@/app/breadcrumbs"
import { StewardMark } from "@/app/steward-mark"
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
 * Till, 2026-09-17, ordered one shape and left three questions open; nine shells were built to
 * answer them and he picked one, so what is left here is that one - the pill at the top left
 * carrying the navigation toggle and the path of the page, which grows into the head of the
 * sidebar when the navigation opens. The header is gone with its border, so nothing here may look
 * like a bar: the island is a small bordered surface lying on the background and has no height of
 * its own beyond what it needs.
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

/**
 * The mark and the word, standing in for "Overview" as the first crumb (steward/89).
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
 * THE ISLAND, WHICH IS ALSO THE HEAD OF THE SIDEBAR - one element, two shapes.
 *
 * Collapsed it is a small bordered pill at the top left carrying the toggle and the path of the
 * page. Open the navigation and the same element widens to the column's width, drops its border
 * and takes the column's own surface: the head of the sidebar is not a second thing that appears
 * beside the island, it is the island grown. That is what Till picked out of nine shells on
 * 2026-09-17, and everything below is the four corrections he asked for on the same day.
 *
 * <h2>Expanded it carries the mark and the word, and not the path</h2>
 * Till, 2026-09-17: expanded, the head shows only the logo, the word "Steward" and the sidebar
 * icon, and the path is hidden. That is more than a preference once the column is open: the path
 * is the answer to "where am I", and a standing list of every page with the current one marked in
 * blue answers it better than a trail does. Two answers to one question is the weaker one winning
 * half the time. Collapsed, the list is gone and the trail is the only answer there is, so that is
 * exactly where it lives.
 *
 * <h2>No border, and a seam that meets rather than covers</h2>
 * Expanded there is no border at all - not under it, which is the line Till saw, and not to the
 * right of it either: the column already draws that one. The width is one pixel short of
 * `--sidebar-width` for that reason. At the full width this element lies over the column's own
 * right border, which is the fourth correction: the island was covering the column's
 * own right border, and a border does not stop existing because something opaque stands on it.
 *
 * <h2>The height is the clearance, and both are one number</h2>
 * `--island-clearance` in `index.css` is this element's box worked out from the control it is
 * built around, and the head of the column and the scrolling content both keep clear by that same
 * variable. The 4.5rem they used to carry separately was written for a finger and was 16px too
 * tall on a machine with a mouse - which is the third correction, and it was a duplicated number
 * rather than a wrong one.
 */
export function Island({
  crumbs,
  expanded,
  onToggle,
}: {
  crumbs: Crumb[]
  expanded: boolean
  onToggle: () => void
}) {
  return (
    <div
      className={`pointer-events-auto fixed left-0 top-(--island-top) z-30 flex items-center gap-0.5 p-1 transition-[width,border-radius,background-color] duration-200 ease-out ${
        expanded
          ? "w-[calc(var(--sidebar-width)-1px)] rounded-none border-0 bg-sidebar text-sidebar-foreground"
          : "w-[min(20rem,calc(100vw-2rem))] rounded-lg border border-border bg-card text-foreground shadow-sm"
      }`}
    >
      <SidebarToggle expanded={expanded} onToggle={onToggle} rotate />
      {expanded ? (
        // A plain anchor, like every crumb in `Crumbs` - the trail this replaces is built from
        // `BreadcrumbLink`, which is one too, and a router `Link` here would make the head the one
        // element in the island that cannot be rendered without a route tree.
        <a
          href={crumbs[0]?.href ?? "/"}
          className="min-w-0 rounded-md px-1 text-sm font-medium transition-colors duration-150 ease-out hover:text-primary focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          <BrandLabel />
        </a>
      ) : (
        <div className="min-w-0 flex-1 px-1">
          <Crumbs crumbs={crumbs} brand />
        </div>
      )}
    </div>
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
