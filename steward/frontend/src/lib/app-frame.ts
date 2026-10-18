/**
 * Publishes the window height and the home screen's blur band as `--app-height` and `--blur-clearance`.
 *
 * Viewport units are too tall on an iOS home screen, and WebKit can leave its layout viewport short after the keyboard or a long pause. A keyboard or pinch is ignored only when it shrinks the viewport. `--visible-height` and `--keyboard-inset` follow the keyboard instead.
 */

export type ViewLike = {
  innerHeight: number
  visualViewport?: { height: number; scale: number } | null
}

const EDITABLE = new Set(["INPUT", "TEXTAREA", "SELECT"])

function isEditable(element: Element | null): boolean {
  if (!element) return false
  if (EDITABLE.has(element.tagName)) return true
  return element instanceof HTMLElement && element.isContentEditable
}

/**
 * Returns the window's height in pixels, or `null` to keep the last good number. A height above `last` is taken whoever has the focus.
 */
export function measuredHeight(view: ViewLike, focused: Element | null, last: number | null = null): number | null {
  const visual = view.visualViewport
  if (!visual) return view.innerHeight > 0 ? view.innerHeight : null
  if (visual.height <= 0) return null
  if (last !== null && visual.height > last) return visual.height
  if (visual.scale !== 1) return null
  if (isEditable(focused)) return null
  return visual.height
}

export type BandView = {
  innerHeight: number
  visualViewport?: { height: number; scale: number; offsetTop?: number } | null
}

/**
 * Returns what the keyboard leaves visible, in the pixels that `position: fixed` uses, or `null` while pinched. `below` includes iOS's pan, so a sheet meets the keyboard's edge.
 */
export function visibleBand(view: BandView): { height: number; below: number } | null {
  const visual = view.visualViewport
  if (!visual) return view.innerHeight > 0 ? { height: view.innerHeight, below: 0 } : null
  if (visual.height <= 0 || visual.scale !== 1) return null
  return { height: visual.height, below: Math.max(0, view.innerHeight - (visual.offsetTop ?? 0) - visual.height) }
}

export type ScreenLike = {
  innerWidth: number
  screen: { width: number; height: number }
  /** `object`, because `Navigator` declares no `standalone`. */
  navigator: object
}

/**
 * Returns the screen's height in the window's orientation for an iOS home screen app, else `null`. The orientation comes from the width, because iOS never swaps `screen.width` and `screen.height`. A width that is neither side is a window beside another app.
 */
export function screenHeight(view: ScreenLike): number | null {
  if ((view.navigator as { standalone?: boolean }).standalone !== true) return null
  const short = Math.min(view.screen.width, view.screen.height)
  const long = Math.max(view.screen.width, view.screen.height)
  if (short <= 0) return null
  if (Math.abs(view.innerWidth - short) <= 1) return long
  if (Math.abs(view.innerWidth - long) <= 1) return short
  return null
}

export function isStandalone(view: Window): boolean {
  const legacy = (view.navigator as Navigator & { standalone?: boolean }).standalone
  if (legacy === true) return true
  return view.matchMedia?.("(display-mode: standalone)").matches
}

/**
 * The cushion below iOS's blurred band, beyond `env(safe-area-inset-top)`, in pixels. One step of the spacing scale, added only on a home screen.
 */
export const BLUR_CLEARANCE_PX = 8

/**
 * Milliseconds between measurements while the window is visible. It catches resumes that fire no event.
 */
const POLL_INTERVAL_MS = 1000

export function trackAppFrame(view: Window = window): () => void {
  const root = view.document.documentElement

  let published: string | null = null

  let measured: number | null = null

  let band: string | null = null

  const publishBand = () => {
    const visible = visibleBand(view)
    if (!visible) return
    const next = `${Math.round(visible.height)}px ${Math.round(visible.below)}px`
    if (next === band) return
    band = next
    root.style.setProperty("--visible-height", `${Math.round(visible.height)}px`)
    root.style.setProperty("--keyboard-inset", `${Math.round(visible.below)}px`)
  }

  const settle = () => {
    if (isEditable(view.document.activeElement)) return
    if (view.scrollY !== 0 || view.scrollX !== 0) view.scrollTo(0, 0)
  }

  const apply = () => {
    settle()
    publishBand()
    const height = screenHeight(view) ?? measuredHeight(view, view.document.activeElement, measured)
    if (height === null) return
    measured = height
    const next = `${Math.round(height)}px`
    if (next === published) return
    published = next
    root.style.setProperty("--app-height", next)
  }

  let timer: ReturnType<typeof setInterval> | null = null

  const startPolling = () => {
    if (timer === null) timer = setInterval(apply, POLL_INTERVAL_MS)
  }

  const stopPolling = () => {
    if (timer === null) return
    clearInterval(timer)
    timer = null
  }

  const syncPolling = () => {
    if (view.document.visibilityState === "hidden") stopPolling()
    else startPolling()
  }

  root.style.setProperty("--blur-clearance", isStandalone(view) ? `${BLUR_CLEARANCE_PX}px` : "0px")
  apply()

  const visual = view.visualViewport
  view.addEventListener("resize", apply)
  view.addEventListener("orientationchange", apply)
  visual?.addEventListener("resize", apply)
  visual?.addEventListener("scroll", publishBand)
  view.addEventListener("focusout", apply)
  view.addEventListener("pageshow", apply)
  view.addEventListener("focus", apply)
  const onVisibilityChange = () => {
    apply()
    syncPolling()
  }
  view.document.addEventListener("visibilitychange", onVisibilityChange)
  syncPolling()

  return () => {
    view.removeEventListener("resize", apply)
    view.removeEventListener("orientationchange", apply)
    visual?.removeEventListener("resize", apply)
    visual?.removeEventListener("scroll", publishBand)
    view.removeEventListener("focusout", apply)
    view.removeEventListener("pageshow", apply)
    view.removeEventListener("focus", apply)
    view.document.removeEventListener("visibilitychange", onVisibilityChange)
    stopPolling()
  }
}
