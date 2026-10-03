import type { MessageArg, MessageEntry } from "@/lib/api"

export type Language = "en" | "de"

export function packagedOf(entry: MessageEntry, language: Language): string | undefined {
  return language === "en" ? entry.english : entry.german
}

export function overrideOf(entry: MessageEntry, language: Language): string | undefined {
  return language === "en" ? entry.overrideEnglish : entry.overrideGerman
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
