import { useEffect } from "react"
import { useRouterState } from "@tanstack/react-router"

/**
 * Which of the three shells is drawn, for as long as steward/89 is a choice rather than a decision.
 *
 * Three app shells exist side by side so that one person can hold a phone and compare them. They
 * are chosen with `?shell=b` or `?shell=c` on any address of the interface - there is no route of
 * their own and no entry in the navigation, because the whole question is how a shell feels on the
 * pages that already exist, not on one prepared example.
 *
 * **The choice outlives the link that made it.** A sidebar link goes to `/services/smp` without
 * carrying the query on, so a shell picked once and then navigated away from would last exactly one
 * page - which is the opposite of what a comparison needs. The answer is remembered for the tab and
 * nothing longer: `sessionStorage`, so a second tab can hold a second shell and neither of them
 * survives the browser being closed. When steward/89 is decided this module and the two shells
 * nobody picked are deleted together.
 */
export type ShellVariant = "a" | "b" | "c"

/** The tab-scoped memory of the last shell that was asked for by name. */
export const SHELL_STORAGE_KEY = "steward.shell"

/** Anything that is not `b` or `c` is `a`, including nothing at all and including nonsense. */
function normalise(value: string | null | undefined): ShellVariant {
  const asked = value?.trim().toLowerCase()
  return asked === "b" || asked === "c" ? asked : "a"
}

/**
 * The shell this address asks for, or the one this tab was last told about.
 *
 * Pure, and separated from the hook below for exactly one reason: the interesting half is the
 * precedence - an explicit `?shell=` always wins over the memory, so a link can always take the
 * reader somewhere else - and that half needs no router to be tested.
 */
export function resolveShellVariant(
  searchStr: string,
  remembered: string | null,
): ShellVariant {
  const asked = new URLSearchParams(searchStr).get("shell")
  return asked === null ? normalise(remembered) : normalise(asked)
}

/** Reads the tab's memory, and survives a browser that refuses storage at all. */
export function rememberedVariant(): string | null {
  try {
    return window.sessionStorage.getItem(SHELL_STORAGE_KEY)
  } catch {
    return null
  }
}

export function useShellVariant(): ShellVariant {
  const searchStr = useRouterState({ select: (state) => state.location.searchStr })
  const variant = resolveShellVariant(searchStr, rememberedVariant())

  useEffect(() => {
    try {
      window.sessionStorage.setItem(SHELL_STORAGE_KEY, variant)
    } catch {
      // A browser with storage switched off still gets a working interface, it just forgets the
      // shell on the next page. Nothing here is worth an error boundary.
    }
  }, [variant])

  return variant
}

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
