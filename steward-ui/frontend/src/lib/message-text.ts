import type { MessageArg, MessageEntry } from "@/lib/api"

export type Language = "en" | "de"

export function packagedOf(entry: MessageEntry, language: Language): string | undefined {
  return language === "en" ? entry.english : entry.german
}

export function overrideOf(entry: MessageEntry, language: Language): string | undefined {
  return language === "en" ? entry.overrideEnglish : entry.overrideGerman
}

/** How a placeholder is written in a text: `{name}`, or `<name>` for one filled by a component. */
export function tokenOf(arg: MessageArg): string {
  return arg.component ? `<${arg.name}>` : `{${arg.name}}`
}

/** The worker's `DECLARABLE`: what a spec could declare, so what a typo in one looks like. */
const DECLARABLE = /\{[A-Za-z0-9_.-]+\}|<_[A-Za-z0-9_-]+>/g

/**
 * The placeholders in `text` that the key's spec does not declare, in the order they appear - the
 * same answer as `MessageBundles.unknownPlaceholders` on the worker, which refuses a save that has
 * one. A key no spec describes is never checked, there as here.
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
