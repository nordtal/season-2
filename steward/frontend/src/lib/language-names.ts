/**
 * Display names for the `languages` tags in the bot's `access` group, one per message bundle the repo ships.
 *
 * An unknown tag falls back to itself, which beats a wrong guess at a name.
 */
const LANGUAGE_NAMES: Record<string, string> = {
  en: "English",
  de: "Deutsch",
}

export function languageName(tag: string): string {
  const key = tag.trim().toLowerCase()
  return LANGUAGE_NAMES[key] ?? tag
}
