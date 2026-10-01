import type {
  ConfigDocument,
  ConfigEntry,
  ConfigLocation,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
} from "@/lib/api"

/**
 * Settings search, for one service's files and for the command palette across every file and message bundle.
 *
 * Both paths go through these functions, so the rule that a secret is never searched lives in one place.
 */

/** A `MAP` entry is a heading with no value of its own, so a hit on it would jump nowhere. */
export function isSearchable(entry: ConfigEntry): boolean {
  return entry.kind !== "MAP"
}

/**
 * What one entry is found by, lower-cased: label, path, explanation and, never for a secret, its value.
 *
 * The secret check is repeated here so a search can never guess a secret, whatever the wire sent.
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

/** Whether `entry` is found by `query`, as a case-insensitive substring match. */
export function matchesQuery(entry: ConfigEntry, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return false
  if (!isSearchable(entry)) return false
  return entryHaystack(entry).includes(needle)
}

/** One hit in a config file: the file and the entry that matched. */
export type ConfigSettingsHit = {
  kind: "config"
  location: ConfigLocation
  entry: ConfigEntry
}

/**
 * One hit in a message bundle: the bundle, the language the match was found in, and the key.
 *
 * A match on the key or in both bodies yields one hit per language, as each language is its own place.
 */
export type MessageSettingsHit = {
  kind: "message"
  location: MessageBundleLocation
  language: "en" | "de"
  entry: MessageEntry
}

/** One hit from either supplier, as `config-search.tsx` and `command-palette.tsx` read it. */
export type SettingsHit = ConfigSettingsHit | MessageSettingsHit

/**
 * Every hit across a set of groups, given each group's fetched document or `undefined`.
 */
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
 * What one language of one bundle entry is found by, lower-cased: the key, the packaged text and the override.
 *
 * Only this language's text is read, so an English query never finds a bundle by its German line.
 */
export function messageEntryHaystack(entry: MessageEntry, language: "en" | "de"): string {
  const packaged = language === "en" ? entry.english : entry.german
  const override = language === "en" ? entry.overrideEnglish : entry.overrideGerman
  const parts = [entry.key]
  if (packaged) parts.push(packaged)
  if (override) parts.push(override)
  return parts.join("\n").toLowerCase()
}

/** Whether `entry`'s `language` row is found by `query`, the same substring match as {@link matchesQuery}. */
export function matchesMessageQuery(entry: MessageEntry, language: "en" | "de", query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return false
  return messageEntryHaystack(entry, language).includes(needle)
}

/** Every hit across a set of bundles, given each bundle's fetched document, like {@link searchAcross}. */
export function searchMessagesAcross(
  bundles: Array<{ location: MessageBundleLocation; bundle: MessageBundle | undefined }>,
  query: string,
): MessageSettingsHit[] {
  if (!query.trim()) return []
  const hits: MessageSettingsHit[] = []
  for (const { location, bundle } of bundles) {
    if (!bundle) continue
    for (const entry of bundle.entries) {
      for (const language of ["en", "de"] as const) {
        /** A language with neither packaged text nor an override is skipped, so a key match yields no duplicate row. */
        const packaged = language === "en" ? entry.english : entry.german
        const override = language === "en" ? entry.overrideEnglish : entry.overrideGerman
        if (packaged === undefined && override === undefined) continue
        if (matchesMessageQuery(entry, language, query)) {
          hits.push({ kind: "message", location, language, entry })
        }
      }
    }
  }
  return hits
}

/** Config hits then message hits, as one list, for the command palette's global search. */
export function searchSettingsAndMessages(
  configs: Array<{ location: ConfigLocation; document: ConfigDocument | undefined }>,
  messages: Array<{ location: MessageBundleLocation; bundle: MessageBundle | undefined }>,
  query: string,
): SettingsHit[] {
  return [...searchAcross(configs, query), ...searchMessagesAcross(messages, query)]
}

/**
 * How a searchable row is written down: its name on the first line, its context after it.
 *
 * {@link rankValue} ranks a match in the name above every match in the context.
 */
export function searchValue(name: string, ...context: Array<string | undefined | null>): string {
  return [name, ...context.filter((part): part is string => Boolean(part))].join("\n")
}

/** Lower-cased, with runs of whitespace, hyphens and underscores flattened to one space; dots are kept. */
function normalise(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s\-_]+/g, " ")
    .trim()
}

/** Whether the match at `at` starts a word rather than landing in the middle of one. */
function atWordStart(haystack: string, at: number): boolean {
  return at === 0 || /[ ./:#]/.test(haystack[at - 1] ?? "")
}

/**
 * What one row scores against what was typed; 0 means no match and hides the row.
 *
 * A substring match, not cmdk's subsequence, because cmdk cannot reorder its groups by score.
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

/** The haystack a hit is ranked by, the same text each caller hands the palette. */
export function hitValue(hit: SettingsHit): string {
  return hit.kind === "config" ? entryHaystack(hit.entry) : messageEntryHaystack(hit.entry, hit.language)
}

/** Best first, ties in their original order, since `sort` is stable. */
export function rankHits<T extends SettingsHit>(hits: T[], query: string): T[] {
  return [...hits].toSorted((a, b) => rankValue(hitValue(b), query) - rankValue(hitValue(a), query))
}

/** Where a hit found outside a service's own page hands its target to that page. */
export type PendingJump = { file: string; path: string }

/** A module-level map, since the palette and the service page it opens are never mounted together. */
const pendingJumps = new Map<string, PendingJump>()

/** Subscribers, since a jump to the page already open remounts nothing and would otherwise wait for the next visit. */
const jumpSubscribers = new Set<() => void>()

export function setPendingJump(service: string, jump: PendingJump): void {
  pendingJumps.set(service, jump)
  jumpSubscribers.forEach((subscriber) => subscriber())
}

/**
 * Notifies `listener` every time any service's config jump is set; the caller filters by service.
 *
 * @returns the unsubscribe function, for a `useEffect` cleanup
 */
export function onPendingJump(listener: () => void): () => void {
  jumpSubscribers.add(listener)
  return () => jumpSubscribers.delete(listener)
}

/** Reads and clears in one step, so a jump is consumed exactly once. */
export function takePendingJump(service: string): PendingJump | undefined {
  const jump = pendingJumps.get(service)
  pendingJumps.delete(service)
  return jump
}

/**
 * Where a bundle hit hands its destination to the messages tool: bundle, language tab and key.
 *
 * Kept apart from {@link PendingJump} so a bundle hit can never be taken as a config jump.
 */
export type PendingMessageJump = { path: string; language: "en" | "de"; key: string }

const pendingMessageJumps = new Map<string, PendingMessageJump>()

/** Subscribers, since the Settings tab may already be open when the palette picks a hit on the same page. */
const messageJumpSubscribers = new Set<() => void>()

export function setPendingMessageJump(service: string, jump: PendingMessageJump): void {
  pendingMessageJumps.set(service, jump)
  messageJumpSubscribers.forEach((subscriber) => subscriber())
}

/** Reads and clears in one step, consumed exactly once like {@link takePendingJump}. */
export function takePendingMessageJump(service: string): PendingMessageJump | undefined {
  const jump = pendingMessageJumps.get(service)
  pendingMessageJumps.delete(service)
  return jump
}

/**
 * Notifies `listener` every time any service's message jump is set; the caller filters by service.
 *
 * @returns the unsubscribe function, for a `useEffect` cleanup
 */
export function onPendingMessageJump(listener: () => void): () => void {
  messageJumpSubscribers.add(listener)
  return () => messageJumpSubscribers.delete(listener)
}
