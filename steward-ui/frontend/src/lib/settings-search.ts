import type {
  ConfigDocument,
  ConfigEntry,
  ConfigLocation,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
} from "@/lib/api"

/**
 * Settings search (steward/58), in both the shapes the ticket asked for: a box confined to one
 * service's own files, and the command palette's search across every file the mount holds. Both
 * end up calling the functions below, so "which four things does a query match" is answered once
 * rather than twice - and so the one rule that actually matters, the one about a secret, is
 * enforced in exactly one place instead of trusted to be repeated correctly at every call site.
 *
 * steward/87 adds a second supplier - the message bundles of steward/48 - rather than a second
 * search. `searchAcross` (config files) and `searchMessagesAcross` (bundles) below share the same
 * shape of question ("does this haystack contain this needle, case-insensitively") and both feed
 * {@link SettingsHit}, the one type both callers - `config-search.tsx` and `command-palette.tsx` -
 * already read. A bundle has no `secret` key, so the guard that matters for config has nothing to
 * do on the message side; what changes there is the *location* a hit carries, since a bundle's
 * identity is four parts (service, module, language, key) rather than a file's three.
 */

/**
 * A `MAP` entry is a heading with no value of its own, and past two levels of depth it is not even
 * drawn - `configuration.tsx`'s `Field` returns `null` for it. Matching one into a hit would be a
 * result that jumps nowhere, so it is excluded here rather than filtered out again by every caller.
 */
export function isSearchable(entry: ConfigEntry): boolean {
  return entry.kind !== "MAP"
}

/**
 * What one entry is found by, lower-cased: its label, its path, its explanation (the schema's text,
 * or the file's own mechanical comments when there is no schema), and - **never for a secret** -
 * its current value or list items.
 *
 * The `secret` check does not lean on `value` already being absent over the wire for a secret (it
 * is, see `ConfigEntry` in `lib/api.ts`) - it is repeated here so this function is still correct on
 * its own if an entry ever carried both `secret: true` and a `value` by mistake. A search whose
 * hit/no-hit can be used to guess a secret is a leak with a search box in front of it; this is the
 * one place that guess is refused, on purpose, regardless of what the wire happened to send.
 */
export function entryHaystack(entry: ConfigEntry): string {
  const parts = [entry.label, entry.path]
  if (entry.explanation) parts.push(entry.explanation)
  if (entry.comments.length > 0) parts.push(...entry.comments)
  if (!entry.secret) {
    if (entry.value) parts.push(entry.value)
    if (entry.items && entry.items.length > 0) parts.push(...entry.items)
  }
  return parts.join("\n").toLowerCase()
}

/** Whether `entry` is found by `query` - a plain case-insensitive substring match, not fuzzy. */
export function matchesQuery(entry: ConfigEntry, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return false
  if (!isSearchable(entry)) return false
  return entryHaystack(entry).includes(needle)
}

/** One hit in a config file: which file it lives in, and which of its entries matched. */
export type ConfigSettingsHit = {
  kind: "config"
  location: ConfigLocation
  entry: ConfigEntry
}

/**
 * One hit in a message bundle: which bundle, which of its two languages the match was found in,
 * and which key.
 *
 * `language` is part of the hit rather than the bundle's location, because one key can match in
 * only one of the two - "Abgabe" is German and matches nothing in the English row of the same key.
 * A query that matches the *key* (shared by both languages) or matches in both bodies produces one
 * hit per language, which is deliberate: each is a different destination once `en`/`de` in the
 * messages tool is part of "where" a hit is (see `messages.tsx`), so two languages are two places
 * to jump to, not one place shown twice.
 */
export type MessageSettingsHit = {
  kind: "message"
  location: MessageBundleLocation
  language: "en" | "de"
  entry: MessageEntry
}

/** One hit, from either supplier - what both `config-search.tsx` and `command-palette.tsx` read. */
export type SettingsHit = ConfigSettingsHit | MessageSettingsHit

/**
 * Every hit across a set of files, given each file's already-fetched document (or `undefined` -
 * still loading, failed, or never asked for).
 *
 * A raw document (steward/56 - a foreign file, or a `.yml` steward could not parse) has no
 * `entries` and matches nothing: there is no key here for a hit to point at.
 */
export function searchAcross(
  files: Array<{ location: ConfigLocation; document: ConfigDocument | undefined }>,
  query: string,
): ConfigSettingsHit[] {
  if (!query.trim()) return []
  const hits: ConfigSettingsHit[] = []
  for (const { location, document } of files) {
    if (!document || document.raw) continue
    for (const entry of document.entries) {
      if (matchesQuery(entry, query)) hits.push({ kind: "config", location, entry })
    }
  }
  return hits
}

// --- message bundles (steward/87) ---------------------------------------------------------------

/**
 * What one language of one bundle entry is found by, lower-cased: the key (shared by both
 * languages - "who searches the key means the key", per the ticket), the packaged text that ships
 * in the jar, and the operator's override, when either is present for this language.
 *
 * There is no secret to exclude here - a bundle carries no `secret` key at all - so unlike
 * {@link entryHaystack} this has nothing to refuse. What it does have to get right is staying
 * per-language: reading the *other* language's text into this haystack would make an English-only
 * query find a bundle by its German line, which is not what the ticket asked for when it named
 * the default text and the translation as the two places to search - each language is its own
 * haystack, not one combined one.
 */
export function messageEntryHaystack(entry: MessageEntry, language: "en" | "de"): string {
  const packaged = language === "en" ? entry.english : entry.german
  const override = language === "en" ? entry.overrideEnglish : entry.overrideGerman
  const parts = [entry.key]
  if (packaged) parts.push(packaged)
  if (override) parts.push(override)
  return parts.join("\n").toLowerCase()
}

/** Whether `entry`'s `language` row is found by `query` - the same plain substring match as
 * {@link matchesQuery}, over the message haystack instead of the config one. */
export function matchesMessageQuery(entry: MessageEntry, language: "en" | "de", query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) return false
  return messageEntryHaystack(entry, language).includes(needle)
}

/**
 * Every hit across a set of bundles, given each bundle's already-fetched document - the message
 * side of {@link searchAcross}, same shape: skip what has not loaded, check both languages of
 * every entry, keep what matches.
 */
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
        // A language with neither packaged text nor an override for this key is not a place to
        // jump to - measured on this host, 2026-09-17, every key in all five bundles carries both
        // languages, so this only matters for a malformed bundle. Without it, a query that matches
        // only the key (shared by both languages, see `messageEntryHaystack`) would still produce
        // a "de" hit for an English-only key, identical in every visible respect to the "en" one
        // it sits next to - two rows a person cannot tell apart is worse than one.
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

/**
 * Both suppliers, one list, not two groups (Till, steward/87): config hits and
 * message hits are concatenated rather than grouped, config first only because that preserves the
 * order the two existing callers already drew config hits in before this ticket. Neither caller is
 * required to use this - `config-search.tsx`'s per-service box calls both suppliers directly, since
 * it already had its own config-only call before this ticket and folding it into one function here
 * would have hidden, rather than shown, that it now has two - but `command-palette.tsx`'s global
 * search does, because there is no such history to preserve.
 */
export function searchSettingsAndMessages(
  configs: Array<{ location: ConfigLocation; document: ConfigDocument | undefined }>,
  messages: Array<{ location: MessageBundleLocation; bundle: MessageBundle | undefined }>,
  query: string,
): SettingsHit[] {
  return [...searchAcross(configs, query), ...searchMessagesAcross(messages, query)]
}

// --- carrying a hit across a navigation ---------------------------------------------------------

/** Where a hit found outside a service's own page hands a hit to it. */
export type PendingJump = { file: string; path: string }

/**
 * A plain module-level map, not React state or a route search param.
 *
 * The command palette (global search, `app/command-palette.tsx`) and the service page it navigates
 * to are never mounted at the same time, so there is no shared ancestor to lift this into, and a
 * route param would need `/services/$name` to grow a `validateSearch` for two fields nothing else
 * uses. This survives the navigation the same way any module-level variable does in a
 * single-page app: nothing ever unloads the module in between.
 */
const pendingJumps = new Map<string, PendingJump>()

export function setPendingJump(service: string, jump: PendingJump): void {
  pendingJumps.set(service, jump)
}

/**
 * Reads and clears in one step - a jump is consumed exactly once, so navigating back to the same
 * service page later does not silently reopen and re-highlight a field nobody asked for any more.
 */
export function takePendingJump(service: string): PendingJump | undefined {
  const jump = pendingJumps.get(service)
  pendingJumps.delete(service)
  return jump
}

// --- carrying a hit into the messages tool (steward/87) -----------------------------------------

/**
 * Where a bundle hit hands its destination to the messages tool: which bundle, which language tab,
 * and which key to land on and highlight.
 *
 * A separate type and a separate map from {@link PendingJump} on purpose, rather than widening it
 * with an optional `language`. `configuration.tsx` (frozen for this ticket) already destructures
 * `PendingJump` as `{ file, path }` and treats every jump it takes as a config jump; a bundle hit
 * that happened to land in that map by way of a shared `path` field would open nothing, or open the
 * wrong thing, in a file this ticket cannot change to guard against it. Two maps means a bundle hit
 * can only ever be read by the one place that knows what a bundle hit is.
 */
export type PendingMessageJump = { path: string; language: "en" | "de"; key: string }

const pendingMessageJumps = new Map<string, PendingMessageJump>()

/**
 * Unlike {@link pendingJumps}, this map is read by a component that is already mounted just as
 * often as it is read by one arriving fresh from a navigation: the messages tool
 * (`ServiceMessages`, in `messages.tsx`) sits on the very page the per-service search box
 * (`ServiceSettingsSearch`, in `config-search.tsx`) is also drawn on, and a hit found in that box
 * has nowhere to navigate *to* - both are already on screen. So a jump has to reach a component
 * that may already be sitting there, not only one about to mount, which a plain map cannot do on
 * its own - nothing tells an already-rendered subscriber that a new entry showed up. `subscribers`
 * is the difference: `setPendingMessageJump` calls every one of them, and `ServiceMessages` both
 * checks once on mount/service-change (the command-palette-then-navigate case, the same way
 * `configuration.tsx` checks `pendingJumps`) and subscribes for as long as it stays mounted (the
 * same-page case).
 */
const messageJumpSubscribers = new Set<() => void>()

export function setPendingMessageJump(service: string, jump: PendingMessageJump): void {
  pendingMessageJumps.set(service, jump)
  messageJumpSubscribers.forEach((subscriber) => subscriber())
}

/** Reads and clears in one step, the same "consumed exactly once" rule {@link takePendingJump} follows. */
export function takePendingMessageJump(service: string): PendingMessageJump | undefined {
  const jump = pendingMessageJumps.get(service)
  pendingMessageJumps.delete(service)
  return jump
}

/**
 * Notifies `listener` every time any service's message jump is set - filtering by service is the
 * caller's job, the same way `takePendingMessageJump` takes a service rather than this function
 * taking one. Returns the unsubscribe function, for a `useEffect` cleanup.
 */
export function onPendingMessageJump(listener: () => void): () => void {
  messageJumpSubscribers.add(listener)
  return () => messageJumpSubscribers.delete(listener)
}
