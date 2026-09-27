/** The cookie the sidebar provider writes; shadcn never reads it back, so this file does. */
export const SIDEBAR_COOKIE_NAME = "sidebar_state"

/** Whether the sidebar starts open: yes unless the cookie says closed, so a missing or mangled one keeps it open. */
export function sidebarDefaultOpen(cookie: string): boolean {
  for (const part of cookie.split(";")) {
    const [name, ...rest] = part.trim().split("=")
    if (name === SIDEBAR_COOKIE_NAME) return rest.join("=").trim() !== "false"
  }
  return true
}
