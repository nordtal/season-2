/**
 * What the sidebar remembers between visits.
 *
 * This file used to also hold the `?shell=a` … `?shell=i` machinery that let nine app shells stand
 * side by side while steward/89 was a question. It was written to be deleted once the question was
 * answered, and on 2026-09-17 it was: the chosen frame is `app/frames.tsx` and there is nothing
 * left to choose between, so the query parameter, the tab-scoped memory and the eight other shells
 * went together. What stays is the one piece of remembered shell state that is not a comparison.
 */

/**
 * The name of the cookie the sidebar provider writes when it is opened or closed.
 *
 * shadcn's provider writes this itself and never reads it - reading it back is the application's
 * job, and skipping that job is what makes a collapsed sidebar spring open again on every reload.
 */
export const SIDEBAR_COOKIE_NAME = "sidebar_state"

/**
 * Whether the sidebar starts open, given `document.cookie`.
 *
 * Open unless the cookie says otherwise: a first visit, a cleared browser and a cookie somebody
 * has mangled all mean "the sidebar as it has always looked", never a hidden navigation nobody
 * asked to hide.
 */
export function sidebarDefaultOpen(cookie: string): boolean {
  for (const part of cookie.split(";")) {
    const [name, ...rest] = part.trim().split("=")
    if (name === SIDEBAR_COOKIE_NAME) return rest.join("=").trim() !== "false"
  }
  return true
}
