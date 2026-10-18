import type { MessageEntry } from "@/lib/api"

/** A lowercase language tag like `en`: whatever a bundle ships or an admin has written. */
export type Language = string

/** The language that every text falls back to, listed first. */
export const ENGLISH: Language = "en"

const TAG = /^[a-z]{2,3}(-[a-z0-9]+)*$/

/** Returns whether `value` is a language tag, by the same pattern that Steward accepts. */
export function isLanguage(value: string): value is Language {
  return TAG.test(value)
}

/**
 * Returns the jar's texts of one language, every variant in order, or none when the jar ships none.
 */
export function packagedOf(entry: MessageEntry, language: Language): string[] {
  return entry.texts[language] ?? []
}

/**
 * Returns an admin's texts of one language, every variant in order, or `undefined` while the jar's texts are shown.
 */
export function overrideOf(entry: MessageEntry, language: Language): string[] | undefined {
  return entry.overrides[language]
}

/** Returns what one language shows: its override, else the jar's texts. */
export function shownOf(entry: MessageEntry, language: Language): string[] {
  return overrideOf(entry, language) ?? packagedOf(entry, language)
}

/**
 * Returns the languages of some keys, English first: every language that the network speaks, a jar ships or an admin wrote.
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
 * Returns each bundle's languages, English first. A bundle that ships English only keeps English and what an admin wrote. Every other bundle also offers each language that the network speaks.
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
