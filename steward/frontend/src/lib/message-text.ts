import type { MessageEntry } from "@/lib/api"

/** A lowercase language tag like `en`: whatever a bundle ships or an admin has written. */
export type Language = string

/** The language every text falls back to, listed first. */
export const ENGLISH: Language = "en"

const TAG = /^[a-z]{2,3}(-[a-z0-9]+)*$/

/** Whether `value` is a language tag, the same pattern Steward accepts. */
export function isLanguage(value: string): value is Language {
  return TAG.test(value)
}

/** The jar's texts of one language, every variant in order; none when it ships none. */
export function packagedOf(entry: MessageEntry, language: Language): string[] {
  return entry.texts[language] ?? []
}

/** An admin's texts of one language, every variant in order; `undefined` while the jar's are shown. */
export function overrideOf(entry: MessageEntry, language: Language): string[] | undefined {
  return entry.overrides[language]
}

/** What one language shows: its override, else the jar's texts. */
export function shownOf(entry: MessageEntry, language: Language): string[] {
  return overrideOf(entry, language) ?? packagedOf(entry, language)
}

/** The languages of some keys, English first: every one the network speaks, a jar ships or an admin wrote. */
export function languagesOf(entries: MessageEntry[], network: readonly Language[] = []): Language[] {
  const all = new Set<Language>([ENGLISH, ...network])
  for (const entry of entries) {
    for (const language of Object.keys(entry.texts)) all.add(language)
    for (const language of Object.keys(entry.overrides)) all.add(language)
  }
  return [...all].toSorted((a, b) => (a === ENGLISH ? -1 : b === ENGLISH ? 1 : a.localeCompare(b)))
}

/**
 * Each bundle's languages, English first. A bundle that ships English only keeps to English and what an admin
 * wrote; every other bundle offers each language the network speaks as well.
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
