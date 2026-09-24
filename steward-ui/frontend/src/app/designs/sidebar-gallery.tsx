import { ArrowsOutSimpleIcon } from "@phosphor-icons/react"
import { useEffect, useRef, useState } from "react"

import { SIDEBAR_VARIANTS, sidebarVariant } from "@/app/designs/sidebar-variant"
import type { SidebarVariant } from "@/app/designs/sidebar-variant"

/**
 * The comparison page for the proposed sidebars.
 *
 * Every direction in four live frames - phone and desktop, closed and open - each one the real
 * interface in an iframe, so a frame can be tapped, opened and closed like the thing itself. "Now"
 * is the live frame, drawn the same way, so the proposals are judged against what is there rather
 * than against memory. Deleted with the rest of `app/designs/` once a direction is picked.
 */

type Choice = SidebarVariant | "now"

const NAMES: Record<Choice, string> = {
  now: "The shell that is live today",
  a: "Today's shell, put right",
  b: "No surfaces",
  c: "Thumb dock",
  d: "One card",
}

const PAGE = "/services/smp"

function frameSrc(choice: Choice, nav: "open" | "closed") {
  return `${PAGE}?sidebar=${choice === "now" ? "off" : choice}&nav=${nav}&frame=1`
}

export function SidebarGalleryPage() {
  const [choice, setChoice] = useState<Choice>(sidebarVariant() ?? "a")
  const choices: Choice[] = ["now", ...SIDEBAR_VARIANTS]

  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Sidebar</h1>

      <div role="tablist" aria-label="Proposal" className="flex w-fit rounded-lg border border-border p-0.5">
        {choices.map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={choice === option}
            onClick={() => setChoice(option)}
            className={`min-h-control min-w-11 rounded-md px-3 text-sm transition-colors duration-150 ease-out ${
              choice === option ? "bg-secondary text-foreground" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            {option === "now" ? "Now" : option.toUpperCase()}
          </button>
        ))}
      </div>

      <div className="-mt-3 flex items-center justify-between gap-3">
        <p className="min-w-0 truncate text-sm text-muted-foreground">{NAMES[choice]}</p>
        <a
          href={`${PAGE}?sidebar=${choice === "now" ? "off" : choice}`}
          className="-mr-3 flex min-h-control shrink-0 items-center gap-2 rounded-lg px-3 text-sm text-primary hover:bg-secondary/60"
        >
          <ArrowsOutSimpleIcon className="size-4" aria-hidden />
          Full screen
        </a>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:max-w-[34rem]">
        <Frame key={`${choice}-p-c`} src={frameSrc(choice, "closed")} width={390} height={844} label="Phone, closed" />
        <Frame key={`${choice}-p-o`} src={frameSrc(choice, "open")} width={390} height={844} label="Phone, open" />
      </div>
      <div className="grid grid-cols-1 gap-3 xl:grid-cols-2">
        <Frame key={`${choice}-d-c`} src={frameSrc(choice, "closed")} width={1440} height={900} label="Desktop, closed" />
        <Frame key={`${choice}-d-o`} src={frameSrc(choice, "open")} width={1440} height={900} label="Desktop, open" />
      </div>
    </div>
  )
}

/** One live frame, drawn at the device's real width and scaled down to the space it has. */
function Frame({ src, width, height, label }: { src: string; width: number; height: number; label: string }) {
  const box = useRef<HTMLDivElement>(null)
  const [scale, setScale] = useState(0)

  useEffect(() => {
    const element = box.current
    if (!element) return
    const observer = new ResizeObserver(([entry]) => setScale(entry.contentRect.width / width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [width])

  return (
    <figure className="flex min-w-0 flex-col gap-1.5">
      <div
        ref={box}
        className="relative w-full overflow-hidden rounded-xl border border-border bg-background"
        style={{ height: height * scale }}
      >
        {scale > 0 ? (
          <iframe
            src={src}
            title={label}
            className="absolute top-0 left-0 origin-top-left border-0"
            style={{ width, height, transform: `scale(${scale})` }}
          />
        ) : null}
      </div>
      <figcaption className="text-xs text-muted-foreground">{label}</figcaption>
    </figure>
  )
}
