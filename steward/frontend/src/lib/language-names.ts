/** Names a language tag's language in itself, capitalised, or its language subtag when `Intl` has no name, as the server's `Locales.ownName` does. A tag `Intl` cannot read is shown as itself. */
export function languageName(tag: string): string {
  try {
    const language = new Intl.Locale(tag).language
    const own = new Intl.DisplayNames([tag], { type: "language" }).of(language) ?? language
    return own === language ? language : own.charAt(0).toLocaleUpperCase(tag) + own.slice(1)
  } catch {
    return tag
  }
}
