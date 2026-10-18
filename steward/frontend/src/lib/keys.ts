/**
 * The three fields that this file reads from `Navigator`, so a test can build one without the rest.
 */
export type NavigatorLike = {
  platform?: string
  userAgent?: string
  userAgentData?: { platform?: string }
}

/**
 * Returns whether this platform presses ⌘ and not Ctrl: a Mac, an iPhone or an iPad.
 *
 * The palette listens for both, so a wrong guess costs only a label. `navigator.platform` is deprecated but universal.
 */
export function usesCommandKey(navigatorLike: NavigatorLike | undefined = globalThis.navigator): boolean {
  if (!navigatorLike) return false
  const hinted = navigatorLike.userAgentData?.platform
  const platform = hinted || navigatorLike.platform || navigatorLike.userAgent || ""
  return /mac|iphone|ipad|ipod/i.test(platform)
}

/** Returns `⌘` or `Ctrl`, for a `<kbd>`. */
export function modifierLabel(navigatorLike?: NavigatorLike): string {
  return usesCommandKey(navigatorLike) ? "⌘" : "Ctrl"
}

/** Returns the whole shortcut as one string: `⌘K` on a Mac, `Ctrl+K` elsewhere. */
export function shortcutLabel(key: string, navigatorLike?: NavigatorLike): string {
  return usesCommandKey(navigatorLike) ? `⌘${key}` : `Ctrl+${key}`
}
