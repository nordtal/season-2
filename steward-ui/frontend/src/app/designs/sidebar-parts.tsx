import { Link, Outlet, useRouterState } from "@tanstack/react-router"
import { ListIcon, SidebarIcon, XIcon } from "@phosphor-icons/react"
import { useEffect } from "react"
import type { ReactNode } from "react"

import { activeEntryId } from "@/app/app-sidebar"
import { NAVIGATION } from "@/app/navigation"
import { StewardMark } from "@/app/steward-mark"
import { useServices } from "@/lib/queries"
import { HealthDot } from "@/components/steward/status"
import { ScrollArea } from "@/components/ui/scroll-area"
import { useSidebar } from "@/components/ui/sidebar"

/**
 * What the four proposed sidebars share, so that they differ only where they are meant to.
 *
 * ONE AXIS. The complaint that started this round was uneven spacing, and it had a cause: the
 * island, the head of the column and the list each measured from their own edge. Here every
 * direction measures from one variable, `--gutter` (16px on a phone, 24px from `md` up, the same
 * gutter the page content has), and every glyph that stands at the left - the toggle, the mark,
 * each row's icon - sits in a box of `size-control` whose left edge is on that line. So the icons
 * form one column top to bottom, whichever state the navigation is in.
 */
export const GUTTER = "[--gutter:1rem] md:[--gutter:1.5rem]"

/** A ghost control. Every direction draws its controls without a border of their own. */
export const CONTROL =
  "flex size-control shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors duration-150 ease-out hover:bg-secondary/60 hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"

const MOTION = "duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] motion-reduce:duration-0"
export { MOTION }

/** Whether the navigation is showing on this device, and the one way to change it. */
export function useNav() {
  const { open, openMobile, isMobile, setOpenMobile, toggleSidebar, setOpen } = useSidebar()
  const shown = isMobile ? openMobile : open

  // Escape closes whatever is open, on both devices.
  useEffect(() => {
    if (!shown) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return
      if (isMobile) setOpenMobile(false)
      else setOpen(false)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [shown, isMobile, setOpenMobile, setOpen])

  const follow = () => {
    if (isMobile) setOpenMobile(false)
  }
  const close = () => (isMobile ? setOpenMobile(false) : setOpen(false))
  return { shown, isMobile, toggle: toggleSidebar, follow, close }
}

/** The toggle. `glyph` picks the drawing; `menu` turns into a cross while the navigation is open. */
export function NavToggle({
  shown,
  onToggle,
  glyph,
  className,
}: {
  shown: boolean
  onToggle: () => void
  glyph: "panel" | "menu"
  className?: string
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label="Navigation"
      aria-expanded={shown}
      className={`${CONTROL} ${shown ? "text-foreground" : ""} ${className ?? ""}`}
    >
      {glyph === "panel" ? (
        <SidebarIcon className="size-[1.125rem]" aria-hidden />
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

/** The mark and the word. Always "Steward", in every direction and on every device. */
export function Brand({ onFollow, className }: { onFollow?: () => void; className?: string }) {
  return (
    <Link
      to="/"
      onClick={onFollow}
      className={`flex min-w-0 shrink-0 items-center gap-2 rounded-md text-sm font-semibold tracking-tight transition-colors duration-150 ease-out hover:text-primary focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none ${className ?? ""}`}
    >
      <StewardMark className="size-5 shrink-0" />
      <span className="truncate">Steward</span>
    </Link>
  )
}

/** The label of the page being shown: the last crumb, which is the only one a phone ever had. */
export function usePageLabel() {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const active = activeEntryId(pathname, NAVIGATION)
  for (const group of NAVIGATION) {
    for (const entry of group.entries) if (entry.id === active) return entry.label
  }
  return null
}

/**
 * The list of places. `marker` is the one thing the four directions deliberately vary, so they can
 * be compared on it: blue text, a quiet surface, or brightness alone.
 */
export function NavList({
  onFollow,
  marker,
  className,
}: {
  onFollow: () => void
  marker: "blue" | "surface" | "bright"
  className?: string
}) {
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  const active = activeEntryId(pathname, NAVIGATION)
  const services = useServices()

  const rowBase =
    "group/row flex min-h-control items-center rounded-lg pr-3 text-sm transition-colors duration-150 ease-out focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
  const rowState = (isActive: boolean) => {
    if (marker === "blue")
      return isActive ? "text-primary" : "text-foreground/85 hover:text-foreground"
    if (marker === "surface")
      return isActive
        ? "bg-secondary text-foreground"
        : "text-foreground/85 hover:bg-secondary/50 hover:text-foreground"
    return isActive ? "text-foreground" : "text-muted-foreground hover:text-foreground"
  }

  return (
    <nav aria-label="Pages" className={`flex flex-col gap-4 ${className ?? ""}`}>
      {NAVIGATION.map((group) => (
        <div key={group.id} className="flex flex-col">
          {group.label ? (
            <div className="flex h-7 items-center pl-3 text-xs text-muted-foreground">
              {group.label}
            </div>
          ) : null}
          <ul className="flex flex-col">
            {group.entries.map((entry) => {
              const isActive = entry.id === active
              const service =
                group.id === "services"
                  ? services.data?.services.find((row) => row.service === entry.params?.name)
                  : undefined
              return (
                <li key={entry.id}>
                  <Link
                    to={entry.to}
                    params={entry.params as never}
                    onClick={onFollow}
                    aria-current={isActive ? "page" : undefined}
                    className={`${rowBase} ${rowState(isActive)}`}
                  >
                    <span className="flex size-control shrink-0 items-center justify-center">
                      {entry.icon ? <entry.icon className="size-4" aria-hidden /> : null}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{entry.label}</span>
                    {group.id === "services" ? <HealthDot service={service} /> : null}
                  </Link>
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </nav>
  )
}

/** The one scrolling column. Each direction says how much room its chrome needs at the edges. */
export function Content({ className, children }: { className?: string; children?: ReactNode }) {
  return (
    <ScrollArea className="min-h-0 flex-1">
      <main className={`mx-auto w-full max-w-[110rem] px-(--gutter) ${className ?? ""}`}>
        {children ?? <Outlet />}
      </main>
    </ScrollArea>
  )
}

/** The dimmed page behind a navigation that lies over it. Tapping it closes the navigation. */
export function Scrim({ shown, onClose, className }: { shown: boolean; onClose: () => void; className?: string }) {
  return (
    <div
      aria-hidden
      onClick={onClose}
      className={`fixed inset-0 bg-black/50 transition-opacity ${MOTION} ${shown ? "opacity-100" : "pointer-events-none opacity-0"} ${className ?? ""}`}
    />
  )
}
