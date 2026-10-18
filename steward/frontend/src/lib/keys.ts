export type NavigatorLike = {
  platform?: string
  userAgent?: string
  userAgentData?: { platform?: string }
}

/**
 * Returns whether this platform presses ⌘ and not Ctrl. The palette listens for both, so a wrong guess costs only a label.
 */
export function usesCommandKey(navigatorLike: NavigatorLike | undefined = globalThis.navigator): boolean {
  if (!navigatorLike) return false
  const hinted = navigatorLike.userAgentData?.platform
  const platform = hinted || navigatorLike.platform || navigatorLike.userAgent || ""
  return /mac|iphone|ipad|ipod/i.test(platform)
}

export function modifierLabel(navigatorLike?: NavigatorLike): string {
  return usesCommandKey(navigatorLike) ? "⌘" : "Ctrl"
}

export function shortcutLabel(key: string, navigatorLike?: NavigatorLike): string {
  return usesCommandKey(navigatorLike) ? `⌘${key}` : `Ctrl+${key}`
}
