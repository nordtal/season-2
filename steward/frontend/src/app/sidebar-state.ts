/**
 * The name of the cookie that the sidebar provider writes. shadcn never reads it, so this file does.
 */
export const SIDEBAR_COOKIE_NAME = "sidebar_state"

/**
 * Returns whether the sidebar starts open. It does unless the cookie says closed, so a missing or malformed cookie keeps it open.
 */
export function sidebarDefaultOpen(cookie: string): boolean {
  for (const part of cookie.split(";")) {
    const [name, ...rest] = part.trim().split("=")
    if (name === SIDEBAR_COOKIE_NAME) return rest.join("=").trim() !== "false"
  }
  return true
}
