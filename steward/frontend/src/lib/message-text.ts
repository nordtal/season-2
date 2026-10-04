import type { MessageArg, MessageEntry } from "@/lib/api"

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

/** The languages of some keys, English first: every one a jar ships or an admin wrote. */
export function languagesOf(entries: MessageEntry[]): Language[] {
  const all = new Set<Language>([ENGLISH])
  for (const entry of entries) {
    for (const language of Object.keys(entry.texts)) all.add(language)
    for (const language of Object.keys(entry.overrides)) all.add(language)
  }
  return [...all].toSorted((a, b) => (a === ENGLISH ? -1 : b === ENGLISH ? 1 : a.localeCompare(b)))
}

/** How a placeholder is written in a text: `{name}`, or `<action:name>` around the text of an action. */
export function tokenOf(arg: MessageArg): string {
  return arg.action ? `<action:${arg.name}>` : `{${arg.name}}`
}

/** Steward's `PLACEHOLDER`: what a spec could declare, so what a typo in one looks like. */
const DECLARABLE = /\{[A-Za-z0-9_.-]+\}/g

/**
 * The placeholders in `text` its key's spec does not declare, in order, as `Placeholders.unknown`.
 *
 * Steward refuses a save that has one. A key no spec describes is never checked.
 */
export function unknownPlaceholders(entry: MessageEntry, text: string | null | undefined): string[] {
  if (entry.name == null || !text) return []
  const declared = new Set(entry.args.map(tokenOf))
  const unknown: string[] = []
  for (const match of text.matchAll(DECLARABLE)) {
    if (!declared.has(match[0]) && !unknown.includes(match[0])) unknown.push(match[0])
  }
  return unknown
}
