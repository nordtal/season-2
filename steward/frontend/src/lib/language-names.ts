/** Names a language tag in that language, capitalised as the server's `Locales.ownName` does. A tag `Intl` cannot read is shown as itself. */
export function languageName(tag: string): string {
  try {
    const own = new Intl.DisplayNames([tag], { type: "language" }).of(tag) ?? tag
    return own === tag ? tag : own.charAt(0).toLocaleUpperCase(tag) + own.slice(1)
  } catch {
    return tag
  }
}
