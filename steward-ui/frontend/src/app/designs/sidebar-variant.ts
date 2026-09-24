/**
 * Which of the proposed sidebars is drawn, while the proposals are a question.
 *
 * Written to be deleted: once one direction is picked, it replaces `app/frames.tsx` and this file,
 * the gallery and the three directions that lost go together - the same way the nine shells of the
 * header round went.
 *
 * `?sidebar=a` … `?sidebar=d` picks a direction and `?sidebar=off` goes back to the shell that is
 * live today. A top-level window remembers the choice for the tab, so following a link keeps the
 * direction. A frame inside the gallery never writes it: frames share the tab's session storage
 * with the page around them, and four frames writing four answers would leave whichever finished
 * last in charge of every other frame's next navigation.
 */

export const SIDEBAR_VARIANTS = ["a", "b", "c", "d"] as const
export type SidebarVariant = (typeof SIDEBAR_VARIANTS)[number]

const STORAGE_KEY = "steward.sidebar-variant"

function isVariant(value: string | null): value is SidebarVariant {
  return value !== null && (SIDEBAR_VARIANTS as readonly string[]).includes(value)
}

function inFrame() {
  try {
    return window.self !== window.top
  } catch {
    return true
  }
}

/** Read once per page load: a client-side navigation drops the query and must not drop the answer. */
const params = new URLSearchParams(window.location.search)
const asked = params.get("sidebar")

function resolve(): SidebarVariant | null {
  if (asked === "off") {
    if (!inFrame()) sessionStorage.removeItem(STORAGE_KEY)
    return null
  }
  if (isVariant(asked)) {
    if (!inFrame()) sessionStorage.setItem(STORAGE_KEY, asked)
    return asked
  }
  if (inFrame()) return null
  const stored = sessionStorage.getItem(STORAGE_KEY)
  return isVariant(stored) ? stored : null
}

const variant = resolve()

export function sidebarVariant(): SidebarVariant | null {
  return variant
}

/** `?nav=open` or `?nav=closed`: the state a gallery frame starts in. Anything else is no opinion. */
export function initialNav(): "open" | "closed" | null {
  const nav = params.get("nav")
  return nav === "open" || nav === "closed" ? nav : null
}

/** Whether this window is one of the gallery's frames, which draws no way back to the gallery. */
export function isGalleryFrame() {
  return params.get("frame") === "1" && inFrame()
}
