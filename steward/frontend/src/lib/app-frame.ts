/**
 * Measures the window height and the home screen's blur band as `--app-height` and `--blur-clearance`.
 *
 * Viewport units are too tall on an iOS home screen. A keyboard or pinch is ignored only when it shrinks,
 * and the document is put back at the top once no field has focus.
 *
 * An iOS home screen app takes the screen's height instead: WebKit can leave its layout viewport short after the
 * keyboard or a long pause, and then every viewport number, `position: fixed` included, agrees on the short height.
 *
 * `--visible-height` and `--keyboard-inset` follow the keyboard instead: what it leaves visible and what it covers.
 */

/** What this needs off `window`. A type, so a test can hand it a plain object. */
export type ViewLike = {
  innerHeight: number
  visualViewport?: { height: number; scale: number } | null
}

/** Editable elements: while one of these has focus, a shrunk viewport is the keyboard. */
const EDITABLE = new Set(["INPUT", "TEXTAREA", "SELECT"])

function isEditable(element: Element | null): boolean {
  if (!element) return false
  if (EDITABLE.has(element.tagName)) return true
  return element instanceof HTMLElement && element.isContentEditable
}

/**
 * The window's height in pixels, or `null` to keep the last good number.
 *
 * `last` makes the refusals one-directional: a height above it is taken whoever has focus.
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

/** What {@link visibleBand} needs off `window`; `offsetTop` is how far iOS has panned to show a focused field. */
export type BandView = {
  innerHeight: number
  visualViewport?: { height: number; scale: number; offsetTop?: number } | null
}

/**
 * What the keyboard leaves visible, in the pixels `position: fixed` uses, or `null` while pinched.
 *
 * `below` is the keyboard plus any pan iOS made to show a field, so a sheet standing on it meets the keyboard's edge.
 */
export function visibleBand(view: BandView): { height: number; below: number } | null {
  const visual = view.visualViewport
  if (!visual) return view.innerHeight > 0 ? { height: view.innerHeight, below: 0 } : null
  if (visual.height <= 0 || visual.scale !== 1) return null
  return { height: visual.height, below: Math.max(0, view.innerHeight - (visual.offsetTop ?? 0) - visual.height) }
}

/** What {@link screenHeight} needs off `window`. */
export type ScreenLike = {
  innerWidth: number
  screen: { width: number; height: number }
  /** `object`, since `Navigator` declares no `standalone` and a type of optional keys alone would refuse it. */
  navigator: object
}

/**
 * The screen's height in the window's orientation for an iOS home screen app that fills the screen, else `null`.
 *
 * Only iOS answers `navigator.standalone`, and only there does the app cover the whole screen, status bar and home
 * indicator included. The orientation is read off the width, since iOS never swaps `screen.width` and `screen.height`;
 * a width that is neither side is a window beside another app, whose height is not the screen's.
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

/** Whether this is the app on a home screen; `navigator.standalone` is iOS's older answer. */
export function isStandalone(view: Window): boolean {
  const legacy = (view.navigator as Navigator & { standalone?: boolean }).standalone
  if (legacy === true) return true
  return view.matchMedia?.("(display-mode: standalone)").matches
}

/**
 * The cushion below iOS's blurred band, beyond `env(safe-area-inset-top)`, in pixels.
 *
 * One step of the spacing scale, not a measurement; the shell adds it only on a home screen.
 */
export const BLUR_CLEARANCE_PX = 8

/** How often the height is re-measured while visible, in milliseconds, catching resumes that fire no event. */
const POLL_INTERVAL_MS = 1000

/** Starts publishing both properties and returns the function that stops; called once from `main.tsx`. */
export function trackAppFrame(view: Window = window): () => void {
  const root = view.document.documentElement

  /** The last value written, so the poll only writes on a change. */
  let published: string | null = null

  /** The unrounded height behind `published`, which tells a growing window from a keyboard. */
  let measured: number | null = null

  /** The band last written, so a pan writes only when it moves the band. */
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

  /** iOS scrolls the document to show a focused field and may leave it there; nothing else scrolls it. */
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

  /** Polls only while visible, since iOS can resume a home screen app without any event. */
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
  /** Re-measures on `focusout`, since a field's focus blocked the last measurement. */
  view.addEventListener("focusout", apply)
  /** The events that fire on a resume; which one iOS sends depends on how the app was left. */
  view.addEventListener("pageshow", apply)
  view.addEventListener("focus", apply)
  /** Becoming visible re-measures at once and restarts the poll. */
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
