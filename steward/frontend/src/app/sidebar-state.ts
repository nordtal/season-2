/**
 * The name of the cookie that the sidebar provider writes. shadcn never reads it, so this file does.
 */
export const SIDEBAR_COOKIE_NAME = "sidebar_state"

export function sidebarDefaultOpen(cookie: string): boolean {
  for (const part of cookie.split(";")) {
    const [name, ...rest] = part.trim().split("=")
    if (name === SIDEBAR_COOKIE_NAME) return rest.join("=").trim() !== "false"
  }
  return true
}
