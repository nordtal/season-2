import type { MessageEntry } from "@/lib/api"

/** A lowercase language tag like `en`. */
export type Language = string

export const ENGLISH: Language = "en"

const TAG = /^[a-z]{2,3}(-[a-z0-9]+)*$/

/** Returns whether `value` is a language tag, by the pattern Steward accepts. */
export function isLanguage(value: string): value is Language {
  return TAG.test(value)
}

export function packagedOf(entry: MessageEntry, language: Language): string[] {
  return entry.texts[language] ?? []
}

export function overrideOf(entry: MessageEntry, language: Language): string[] | undefined {
  return entry.overrides[language]
}

export function shownOf(entry: MessageEntry, language: Language): string[] {
  return overrideOf(entry, language) ?? packagedOf(entry, language)
}

/**
 * Returns the first variant a key shows in `language` with its draft applied: `null` in the draft is the packaged text.
 */
export function currentOf(
  entry: MessageEntry,
  draft: Record<Language, string[] | null> | undefined,
  language: Language,
) {
  const typed = draft?.[language]
  return (typed === undefined ? shownOf(entry, language) : (typed ?? packagedOf(entry, language)))[0] ?? ""
}

/**
 * Returns the languages of some keys, English first: those the network speaks, a jar ships or an admin wrote.
 */
export function languagesOf(entries: MessageEntry[], network: readonly Language[] = []): Language[] {
  const all = new Set<Language>([ENGLISH, ...network])
  for (const entry of entries) {
    for (const language of Object.keys(entry.texts)) all.add(language)
    for (const language of Object.keys(entry.overrides)) all.add(language)
  }
  return [...all].toSorted((a, b) => (a === ENGLISH ? -1 : b === ENGLISH ? 1 : a.localeCompare(b)))
}

/**
 * Returns each bundle's languages, English first. A bundle that ships English only keeps English and what an admin wrote.
 */
export function languagesByBundle(entries: MessageEntry[], network: readonly Language[]): Map<string, Language[]> {
  const byBundle = new Map<string, MessageEntry[]>()
  for (const entry of entries) byBundle.set(entry.bundle, [...(byBundle.get(entry.bundle) ?? []), entry])
  const languages = new Map<string, Language[]>()
  for (const [bundle, own] of byBundle) {
    const englishOnly = own.every((entry) => Object.keys(entry.texts).every((language) => language === ENGLISH))
    languages.set(bundle, languagesOf(own, englishOnly ? [] : network))
  }
  return languages
}
