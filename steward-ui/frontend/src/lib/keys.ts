/**
 * Which modifier this browser's people actually press.
 *
 * The header said `⌘K` and the sidebar footer said `Ctrl` - the same key, two answers, and both of
 * them wrong for half the readers. There is no way to detect a keyboard, so this detects the
 * platform, which is the same thing everywhere it matters: a Mac, an iPhone and an iPad use ⌘ and
 * everything else uses Ctrl. The palette itself keeps listening for **both**, so a guess that is
 * wrong costs a label and never a shortcut.
 *
 * `navigator.platform` is deprecated and still the only thing every browser answers. The
 * user-agent's own `platform` is asked first where it exists.
 */
export function usesCommandKey(navigatorLike: Navigator | undefined = globalThis.navigator): boolean {
  if (!navigatorLike) return false
  const hinted = (navigatorLike as Navigator & { userAgentData?: { platform?: string } })
    .userAgentData?.platform
  const platform = hinted || navigatorLike.platform || navigatorLike.userAgent || ""
  return /mac|iphone|ipad|ipod/i.test(platform)
}

/** `⌘` or `Ctrl`, for a `<kbd>`. */
export function modifierLabel(navigatorLike?: Navigator): string {
  return usesCommandKey(navigatorLike) ? "⌘" : "Ctrl"
}

/**
 * The whole shortcut as one string: `⌘K` on a Mac, `Ctrl+K` elsewhere.
 *
 * The plus is part of the difference, not a separate decision - `⌘+K` is not how a Mac writes it
 * and `CtrlK` is not how anything else does.
 */
export function shortcutLabel(key: string, navigatorLike?: Navigator): string {
  return usesCommandKey(navigatorLike) ? `⌘${key}` : `Ctrl+${key}`
}
