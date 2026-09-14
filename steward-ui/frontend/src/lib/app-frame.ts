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
 */
export function measuredHeight(view: ViewLike, focused: Element | null): number | null {
  const visual = view.visualViewport
  if (!visual) return view.innerHeight > 0 ? view.innerHeight : null
  if (visual.scale !== 1) return null
  if (isEditable(focused)) return null
  return visual.height > 0 ? visual.height : null
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
 * how much deeper is not something any log or any browser on this host can say. So this is on
 * purpose generous rather than tight: 20px on a home screen, nothing anywhere else. If it reads as
 * too much space, that is a number to lower, which is the direction worth being wrong in.
 */
export const BLUR_CLEARANCE_PX = 20

/**
 * Starts publishing both properties and returns the function that stops.
 *
 * Called once from `main.tsx`. The properties have CSS fallbacks, so the interface is laid out
 * correctly before this ever runs and on anything where it cannot.
 */
export function trackAppFrame(view: Window = window): () => void {
  const root = view.document.documentElement

  const apply = () => {
    const height = measuredHeight(view, view.document.activeElement)
    if (height !== null) root.style.setProperty("--app-height", `${Math.round(height)}px`)
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

  return () => {
    view.removeEventListener("resize", apply)
    view.removeEventListener("orientationchange", apply)
    visual?.removeEventListener("resize", apply)
    view.removeEventListener("focusout", apply)
  }
}
