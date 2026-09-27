/** The three fields this file reads off `Navigator`, so a test can build one without the rest of it. */
export type NavigatorLike = {
  platform?: string
  userAgent?: string
  userAgentData?: { platform?: string }
}

/**
 * Whether this platform presses ⌘ rather than Ctrl: a Mac, an iPhone or an iPad.
 *
 * The palette listens for both, so a wrong guess costs only a label. `navigator.platform` is deprecated but universal.
 */
export function usesCommandKey(navigatorLike: NavigatorLike | undefined = globalThis.navigator): boolean {
  if (!navigatorLike) return false
  const hinted = navigatorLike.userAgentData?.platform
  const platform = hinted || navigatorLike.platform || navigatorLike.userAgent || ""
  return /mac|iphone|ipad|ipod/i.test(platform)
}

/** `⌘` or `Ctrl`, for a `<kbd>`. */
export function modifierLabel(navigatorLike?: NavigatorLike): string {
  return usesCommandKey(navigatorLike) ? "⌘" : "Ctrl"
}

/** The whole shortcut as one string: `⌘K` on a Mac, `Ctrl+K` elsewhere. */
export function shortcutLabel(key: string, navigatorLike?: NavigatorLike): string {
  return usesCommandKey(navigatorLike) ? `⌘${key}` : `Ctrl+${key}`
}
