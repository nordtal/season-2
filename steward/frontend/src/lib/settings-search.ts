import type { ConfigDocument, ConfigEntry, ConfigLocation, MessageEntry, MessageText } from "@/lib/api"
import { languagesOf, overrideOf, packagedOf, shownOf, type Language } from "@/lib/message-text"

/**
 * Settings search for one service's files and for the command palette. Both paths go through these functions, so the rule that a secret is never searched lives in one place.
 */

/** A `MAP` entry is a heading with no value of its own, so a hit on it would jump nowhere. */
export function isSearchable(entry: ConfigEntry): boolean {
  return entry.kind !== "MAP"
}

/**
 * Returns what one entry is found by, lower-cased: label, path, explanation and, never for a secret, its value. The secret check is repeated, whatever the wire sent.
 */
export function entryHaystack(entry: ConfigEntry): string {
  const parts = [entry.label, entry.path]
  if (entry.explanation) parts.push(entry.explanation)
  if (!entry.secret) {
    if (entry.value) parts.push(entry.value)
    if (entry.items && entry.items.length > 0) parts.push(...entry.items)
  }
  return parts.join("\n").toLowerCase()
}

export function matchesQuery(entry: ConfigEntry, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return false
  if (!isSearchable(entry)) return false
  return entryHaystack(entry).includes(needle)
}

export type ConfigSettingsHit = {
  kind: "config"
  location: ConfigLocation
  entry: ConfigEntry
}

/**
 * One hit among the texts. A match on the key or in both bodies yields one hit per language, because each is its own place.
 */
export type MessageSettingsHit = {
  kind: "message"
  text: MessageText
  language: Language
}

export type SettingsHit = ConfigSettingsHit | MessageSettingsHit

export function searchAcross(
  files: Array<{ location: ConfigLocation; document: ConfigDocument | undefined }>,
  query: string,
): ConfigSettingsHit[] {
  if (!query.trim()) return []
  const hits: ConfigSettingsHit[] = []
  for (const { location, document } of files) {
    if (!document) continue
    for (const entry of document.entries) {
      if (matchesQuery(entry, query)) hits.push({ kind: "config", location, entry })
    }
  }
  return hits
}

/**
 * Returns what one language of one entry is found by, lower-cased: the key, the packaged text and the override. An English query never finds a bundle by its German line.
 */
export function messageEntryHaystack(entry: MessageEntry, language: Language): string {
  return [entry.key, ...packagedOf(entry, language), ...(overrideOf(entry, language) ?? [])].join("\n").toLowerCase()
}

export function matchesMessageQuery(entry: MessageEntry, language: Language, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return false
  return messageEntryHaystack(entry, language).includes(needle)
}

export function searchMessagesAcross(texts: MessageText[], query: string): MessageSettingsHit[] {
  if (!query.trim()) return []
  const hits: MessageSettingsHit[] = []
  for (const text of texts) {
    // Only a language that the key has a text in, so a match on the key alone yields one row per language it has.
    for (const language of languagesOf([text.entry])) {
      if (shownOf(text.entry, language).length === 0) continue
      if (matchesMessageQuery(text.entry, language, query)) hits.push({ kind: "message", text, language })
    }
  }
  return hits
}

export function searchSettingsAndMessages(
  configs: Array<{ location: ConfigLocation; document: ConfigDocument | undefined }>,
  texts: MessageText[],
  query: string,
): SettingsHit[] {
  return [...searchAcross(configs, query), ...searchMessagesAcross(texts, query)]
}

/**
 * Returns a row written down as its name on the first line and its context after it. {@link rankValue} ranks a match in the name above every match in the context.
 */
export function searchValue(name: string, ...context: Array<string | undefined | null>): string {
  return [name, ...context.filter((part): part is string => Boolean(part))].join("\n")
}

/** Flattens runs of whitespace, hyphens and underscores to one space. Dots are kept. */
function normalise(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s\-_]+/g, " ")
    .trim()
}

function atWordStart(haystack: string, at: number): boolean {
  return at === 0 || /[ ./:#]/.test(haystack[at - 1] ?? "")
}

/**
 * Returns what one row scores against what was typed. 0 hides the row. It is a substring match, because cmdk cannot reorder its groups by score.
 */
export function rankValue(value: string, query: string): number {
  const needle = normalise(query)
  if (!needle) return 0
  const [first = "", ...rest] = value.split("\n")
  const name = normalise(first)
  const inName = name.indexOf(needle)
  if (inName >= 0) {
    if (name === needle) return 1
    if (inName === 0) return 0.9
    return atWordStart(name, inName) ? 0.8 : 0.7
  }
  const context = normalise(rest.join(" "))
  const inContext = context.indexOf(needle)
  if (inContext < 0) return 0
  return atWordStart(context, inContext) ? 0.4 : 0.3
}

export function hitValue(hit: SettingsHit): string {
  return hit.kind === "config" ? entryHaystack(hit.entry) : messageEntryHaystack(hit.text.entry, hit.language)
}

export function rankHits<T extends SettingsHit>(hits: T[], query: string): T[] {
  return [...hits].toSorted((a, b) => rankValue(hitValue(b), query) - rankValue(hitValue(a), query))
}

export type PendingJump = { file: string; path: string }

/**
 * A module-level map, because the palette and the service page that it opens are never mounted together.
 */
const pendingJumps = new Map<string, PendingJump>()

/** Subscribers, because a jump to an open page remounts nothing. */
const jumpSubscribers = new Set<() => void>()

export function setPendingJump(service: string, jump: PendingJump): void {
  pendingJumps.set(service, jump)
  jumpSubscribers.forEach((subscriber) => subscriber())
}

/** Notifies `listener` on every config jump. The caller filters by service. */
export function onPendingJump(listener: () => void): () => void {
  jumpSubscribers.add(listener)
  return () => jumpSubscribers.delete(listener)
}

export function takePendingJump(service: string): PendingJump | undefined {
  const jump = pendingJumps.get(service)
  pendingJumps.delete(service)
  return jump
}

export type PendingTextJump = { id: string; language: Language }

/** One slot, because there is one Translations page. It is kept apart from {@link PendingJump}. */
let pendingTextJump: PendingTextJump | undefined

/** Subscribers, because the Translations page may already be open. */
const textJumpSubscribers = new Set<() => void>()

export function setPendingTextJump(jump: PendingTextJump): void {
  pendingTextJump = jump
  textJumpSubscribers.forEach((subscriber) => subscriber())
}

export function takePendingTextJump(): PendingTextJump | undefined {
  const jump = pendingTextJump
  pendingTextJump = undefined
  return jump
}

/** Notifies `listener` on every text jump. */
export function onPendingTextJump(listener: () => void): () => void {
  textJumpSubscribers.add(listener)
  return () => textJumpSubscribers.delete(listener)
}
