/** Display names for the bot's `languages` tags. An unknown tag is shown as itself. */
const LANGUAGE_NAMES: Record<string, string> = {
  en: "English",
  de: "Deutsch",
}

export function languageName(tag: string): string {
  const key = tag.trim().toLowerCase()
  return LANGUAGE_NAMES[key] ?? tag
}
