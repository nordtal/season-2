import { useEffect, useRef, useState, useSyncExternalStore } from "react"

import { screenHeight } from "@/lib/app-frame"

/**
 * A readout of every number the frame's height could come from, switched on in the account menu.
 *
 * It stays on across restarts, so it is already showing when iOS leaves the home screen app short. Its lines are
 * the browser's own property names, read like a log line, not words of the bundle.
 */

/** Where the switch is kept; storage, since the moment it is for often follows a restart of the app. */
const STORAGE_KEY = "steward:viewport-diagnostics"

const listeners = new Set<() => void>()

/** Kept here as well, so the switch still holds for this page where storage refuses it. */
let shown = readStored()

function readStored(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "on"
  } catch {
    return false
  }
}

function setShown(on: boolean) {
  shown = on
  try {
    if (on) window.localStorage.setItem(STORAGE_KEY, "on")
    else window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // No storage: the readout holds until the app restarts.
  }
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Whether the readout is on, and the switch that turns it on or off. */
export function useViewportDiagnostics(): [boolean, (on: boolean) => void] {
  return [
    useSyncExternalStore(
      subscribe,
      () => shown,
      () => false,
    ),
    setShown,
  ]
}

/** How often the readout reads again, in milliseconds; a resume can change the numbers without any event. */
const READ_INTERVAL_MS = 500

/** The readout, placed in the frame so it can mark the frame's bottom edge; nothing while it is off. */
export function ViewportDiagnostics() {
  const [on] = useViewportDiagnostics()
  return on ? <Readout /> : null
}

function Readout() {
  const frameEdge = useRef<HTMLDivElement>(null)
  const probe = useRef<HTMLDivElement>(null)
  const [lines, setLines] = useState<string[]>([])

  useEffect(() => {
    let hiddenAt: number | null = null
    let hiddenFor: number | null = null
    const update = () => setLines(readings(frameEdge.current, probe.current, hiddenFor))
    const onVisibility = () => {
      if (document.visibilityState === "hidden") hiddenAt = Date.now()
      else if (hiddenAt !== null) hiddenFor = Date.now() - hiddenAt
      update()
    }

    update()
    const timer = setInterval(update, READ_INTERVAL_MS)
    const visual = window.visualViewport
    window.addEventListener("resize", update)
    window.addEventListener("scroll", update)
    visual?.addEventListener("resize", update)
    visual?.addEventListener("scroll", update)
    document.addEventListener("visibilitychange", onVisibility)
    return () => {
      clearInterval(timer)
      window.removeEventListener("resize", update)
      window.removeEventListener("scroll", update)
      visual?.removeEventListener("resize", update)
      visual?.removeEventListener("scroll", update)
      document.removeEventListener("visibilitychange", onVisibility)
    }
  }, [])

  return (
    <>
      {/* Takes on the four safe area insets as padding, which is the only way to read them. */}
      <div
        ref={probe}
        aria-hidden
        className="pointer-events-none invisible fixed top-0 left-0 pt-[env(safe-area-inset-top)] pr-[env(safe-area-inset-right)] pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)]"
      />
      {/* The frame's bottom edge in red, the bottom of what iOS thinks is the window in green: apart, they are the strip. */}
      <div
        ref={frameEdge}
        aria-hidden
        className="pointer-events-none absolute inset-x-0 bottom-0 z-[60] h-1 bg-destructive"
      />
      <div aria-hidden className="pointer-events-none fixed inset-x-0 bottom-0 z-[60] h-0.5 bg-success" />
      <pre
        data-viewport-diagnostics
        className="pointer-events-none fixed top-[calc(var(--island-top)+3.5rem)] left-(--gutter) z-[60] max-w-[min(32rem,calc(100vw-2*var(--gutter)))] rounded-md bg-black/85 p-2 font-mono text-[10px] leading-snug whitespace-pre-wrap text-foreground"
      >
        {lines.join("\n")}
      </pre>
    </>
  )
}

/** A computed length as whole pixels; nothing read is zero. */
function px(value: string | undefined): number {
  return Math.round(Number.parseFloat(value ?? "") || 0)
}

/** One line per source, rounded to a pixel except the zoom. */
function readings(frameEdge: HTMLElement | null, probe: HTMLElement | null, hiddenFor: number | null): string[] {
  const root = document.documentElement
  const visual = window.visualViewport
  const inset = probe ? getComputedStyle(probe) : null
  const legacy = (navigator as Navigator & { standalone?: boolean }).standalone
  const script = document.querySelector<HTMLScriptElement>('script[type="module"][src]')

  return [
    `${new Date().toLocaleTimeString("en-GB")}  up ${Math.round(performance.now() / 1000)} s  last hidden ${
      hiddenFor === null ? "–" : `${Math.round(hiddenFor / 1000)} s`
    }`,
    `screen ${screen.width}×${screen.height}  screenHeight ${screenHeight(window) ?? "–"}`,
    `inner ${window.innerWidth}×${window.innerHeight}`,
    visual
      ? `visualViewport ${Math.round(visual.width)}×${Math.round(visual.height)}  offsetTop ${Math.round(visual.offsetTop)}  scale ${visual.scale}`
      : "visualViewport none",
    `clientHeight ${root.clientHeight}  scroll ${Math.round(window.scrollX)},${Math.round(window.scrollY)}`,
    `safe area t ${px(inset?.paddingTop)} r ${px(inset?.paddingRight)} b ${px(inset?.paddingBottom)} l ${px(inset?.paddingLeft)}`,
    `--app-height ${getComputedStyle(root).getPropertyValue("--app-height").trim()}  frame bottom ${
      frameEdge ? Math.round(frameEdge.getBoundingClientRect().bottom) : "–"
    }`,
    `standalone ${legacy ?? "–"}  display-mode ${window.matchMedia?.("(display-mode: standalone)").matches ? "standalone" : "browser"}`,
    `bundle ${script?.src.split("/").pop() ?? "–"}`,
    navigator.userAgent,
  ]
}
