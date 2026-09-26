/**
 * How tall the window actually is, and how far the home screen's blurred band reaches into it.
 *
 * Both are published as custom properties on `<html>` - `--app-height` and `--blur-clearance` - so
 * the shell can be laid out in CSS while the two numbers that CSS gets wrong come from measuring.
 *
 * WHY NOT `100svh`. On a desktop and in mobile Safari the viewport units are the window and there
 * is nothing here to do. Added to an iOS home screen they are not: measured on 2026-09-14, the
 * shell drawn at `h-svh` was cut off about a fifth above the bottom edge, in the page *and* in the
 * sidebar sheet, which is `position: fixed` and sized by the same viewport. One number too large,
 * in two places that only share the viewport, is the viewport - so it is measured instead of asked
 * for. `visualViewport` is the visible window; `innerHeight` is the fallback for anything that has
 * no `visualViewport` (and is the same number everywhere it has been checked).
 *
 * THE TWO MEASUREMENTS THAT MUST BE IGNORED, and they are why {@link measuredHeight} can say no:
 * a soft keyboard shrinks the visual viewport, and so does a pinch. Either would shrink the shell
 * and leave it shrunk. Neither is the window getting smaller, so neither is taken.
 *
 * AND THEY ONLY APPLY TO A SHRINKING ONE (steward/148). Both of those are things that make the
 * visible viewport SMALLER - that is the entire reason they are here. Refusing a measurement that
 * is *larger* than the one currently on screen does not ignore a keyboard, it ignores the window
 * getting bigger, and nothing corrects that afterwards: the resume events and the once-a-second
 * poll all come back through this function and are refused for the same reason, so the shell stays
 * short until the field happens to be left. Measured in Firefox on the dev host on 2026-09-20: a
 * desktop window grown from 700px to 1000px with the cursor in a field left a 300px band of
 * background under the scrolling column, for as long as the cursor stayed there.
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
 * The window's height in pixels, or `null` when this moment cannot be trusted to hold it.
 *
 * `null` is not an error and is not zero: it means "keep the last good number", which is what the
 * caller does with it. A shell that followed the keyboard down would stay down after it closed on
 * every browser that does not fire a second resize.
 *
 * `last` is the height the shell is currently drawn at, and it is what makes the two refusals
 * one-directional. A keyboard and a pinch can only take height away; a measurement above `last` is
 * therefore neither of them, whoever has focus. Without it, the shell is left standing short of the
 * bottom edge with no event able to correct it - steward/148, and the file header has the numbers.
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

/**
 * Whether this is the app on a home screen rather than a page in a browser.
 *
 * `display-mode: standalone` is the standard answer and iOS gives it; `navigator.standalone` is
 * the older iOS-only one and is kept because this is the one place where being wrong is invisible
 * on every machine a test runs on.
 */
export function isStandalone(view: Window): boolean {
  const legacy = (view.navigator as Navigator & { standalone?: boolean }).standalone
  if (legacy === true) return true
  return view.matchMedia?.("(display-mode: standalone)").matches === true
}

/**
 * How far below the top edge a control has to start to be out from under iOS's blurred band.
 *
 * `env(safe-area-inset-top)` is the status bar and the shell already gives it back. The band is
 * drawn deeper than that - the search and sidebar buttons were half under it on 2026-09-14 - and
 * how much deeper is not something any log or any browser on this host can say.
 *
 * <b>It was 20 for one day, and 20 was too much</b> (Till, 2026-09-15, on the phone: the header sat
 * a small piece too low). The first value was chosen generous on purpose, with "lower it" written
 * down as the direction worth being wrong in. This is that lowering.
 *
 * WHY 8 AND NOT A MEASURED NUMBER. Nobody publishes one, and the reason is that the apps that solve
 * this properly do not add a cushion at all: they draw the blurred band themselves - a fixed element
 * exactly `env(safe-area-inset-top)` tall, `backdrop-filter: blur(10px)`, masked with a gradient so
 * it fades out instead of ending in a visible edge - and then put content directly underneath it
 * with no extra space. The cushion here is the cheap half of that idea, and 8px is one step of the
 * spacing scale, not a measurement. If it still reads as too low, the next values are 4 and 0; if 0
 * puts the buttons back under the band, then the cushion is the wrong mechanism and the band we draw
 * ourselves is the right one. That is a bigger change and it is written down in steward/51.
 */
export const BLUR_CLEARANCE_PX = 8

/**
 * How often the height is re-measured while the page is visible, in milliseconds.
 *
 * This is the catch, not the mechanism: the events below are what normally corrects the height, and
 * they do it in the same frame. One second is slow enough to be free and fast enough that a wrong
 * height after a resume is gone before a finger could have fixed it.
 */
const POLL_INTERVAL_MS = 1000

/**
 * Starts publishing both properties and returns the function that stops.
 *
 * Called once from `main.tsx`. The properties have CSS fallbacks, so the interface is laid out
 * correctly before this ever runs and on anything where it cannot.
 */
export function trackAppFrame(view: Window = window): () => void {
  const root = view.document.documentElement

  // The last value actually written. The poll below runs once a second and would otherwise be a
  // style change per second for nothing; comparing costs nothing and is the difference between a
  // cheap fallback and an expensive one. It is also why `apply` is safe to call as often as we like.
  let published: string | null = null

  // The raw height behind `published`, and the thing that tells a growing window from a keyboard.
  // It is the unrounded measurement rather than the string, because the comparison in
  // `measuredHeight` is about direction and half a pixel is not one.
  let measured: number | null = null

  const apply = () => {
    const height = measuredHeight(view, view.document.activeElement, measured)
    if (height === null) return
    measured = height
    const next = `${Math.round(height)}px`
    if (next === published) return
    published = next
    root.style.setProperty("--app-height", next)
  }

  // THE POLL, AND WHY IT EXISTS AT ALL - steward/51, second attempt. The three resume events below
  // were added on 2026-09-15 and the grey band came back on 2026-09-17, so on an iOS home screen a
  // resume can arrive with no event whatsoever. There is nothing left to listen to, so the height is
  // simply re-read on a timer. It runs ONLY while the page is visible: a timer left running in the
  // background is exactly the thing iOS throttles or freezes, and a measurement taken there would be
  // of a window nobody is looking at.
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
  // `focusout`, and it is not decoration. `measuredHeight` refuses to measure while a field has
  // focus, so a window resized - or a phone rotated - with the cursor in a field keeps the height
  // it had before, and nothing else ever fires again to correct it. This is that second event.
  view.addEventListener("focusout", apply)
  // THE THREE THAT FIRE WHEN THE APP COMES BACK, and they are the whole of steward/51. A home-screen
  // app that has been in the background for a while is restored from the back/forward cache with the
  // height it was frozen at. If the bar layout changed in the meantime - and after a long pause on
  // iOS it has - no `resize` and no `visualViewport` event ever arrives to say so, so the last good
  // `--app-height` is simply wrong and stays wrong until the user drags the page. That drag is
  // exactly the workaround Till found. These three are the events that do fire on a resume, and
  // there are three rather than one because which of them iOS sends depends on how the app was left.
  view.addEventListener("pageshow", apply)
  view.addEventListener("focus", apply)
  // Both, and in this order: becoming visible is a resume and has to re-measure at once rather than
  // wait up to a second for the poll, and it is also the moment the poll itself comes back.
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
    view.removeEventListener("focusout", apply)
    view.removeEventListener("pageshow", apply)
    view.removeEventListener("focus", apply)
    view.document.removeEventListener("visibilitychange", onVisibilityChange)
    stopPolling()
  }
}
