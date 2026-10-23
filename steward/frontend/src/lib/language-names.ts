/** Names a language tag in that language, as the server does. A tag `Intl` cannot read is shown as itself. */
export function languageName(tag: string): string {
  try {
    return new Intl.DisplayNames([tag], { type: "language" }).of(tag) ?? tag
  } catch {
    return tag
  }
}
