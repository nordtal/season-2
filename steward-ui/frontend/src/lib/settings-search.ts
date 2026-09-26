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
 * {@link SettingsHit}, the one type `command-palette.tsx` reads. A bundle has no `secret` key, so the guard that matters for config has nothing to
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
 * order the two existing callers already drew config hits in before this ticket. `command-palette.tsx`'s
 * global search is the caller.
 */
export function searchSettingsAndMessages(
  configs: Array<{ location: ConfigLocation; document: ConfigDocument | undefined }>,
  messages: Array<{ location: MessageBundleLocation; bundle: MessageBundle | undefined }>,
  query: string,
): SettingsHit[] {
  return [...searchAcross(configs, query), ...searchMessagesAcross(messages, query)]
}

// --- ranking what was found (steward/105) -------------------------------------------------------

/**
 * How a searchable row is written down: its **name on the first line**, everything else after it.
 *
 * Till, 2026-09-17: typing "Donor" listed every service page and not the setting called Donor.
 * Measured here on 2026-09-18 before anything was changed, with the real `discord-bot/access.yml`
 * off this host: `roles.donor` came 15th of 16 rows, below all ten services, and cmdk's own default
 * filter was not the whole story. Two separate things were wrong.
 *
 * 1. **cmdk's default filter is a subsequence match.** "donor" is d-o-n-o-r, and
 *    "smp Services Log win**d**ow and c**o**nsole f**o**r smp. **r**estart" contains those five
 *    letters in order - so *every* service page matched a query that has nothing to do with any of
 *    them.
 * 2. **Group order cannot be scored around.** cmdk 1.1.1 sorts items *within* their group and then
 *    tries to sort the groups themselves by their best item - but it looks a group up by
 *    `data-value` while holding its React id, so that lookup never matches and the groups keep
 *    their DOM order. Measured the same day: two of this palette's five groups even render
 *    `data-value="undefined"`, having no heading. The Settings group is written last, so **no score
 *    on earth lifts a setting above a page** as long as the page is shown at all.
 *
 * Together those two mean the fix cannot be "weight the name higher"; the unrelated rows have to
 * stop being rows. So the rule here is the same one {@link matchesQuery} has always used for the
 * settings themselves - **a substring, not a subsequence** - and the score only decides the order
 * among rows that genuinely contain what was typed.
 *
 * Callers build a value with {@link searchValue} so that "the name" is a thing this function can
 * find: a page's own label, a run's own title, a setting's label, a bundle key. Everything after
 * the first line is context - a note, a service name, a schema explanation, a current value - and a
 * match there ranks below every match in a name.
 */
export function searchValue(name: string, ...context: Array<string | undefined | null>): string {
  return [name, ...context.filter((part): part is string => Boolean(part))].join("\n")
}

/**
 * Lower-cased, with runs of whitespace, hyphens and underscores flattened to one space.
 *
 * This is the one piece of cmdk's default filter worth keeping: it is what lets "steward ui" find
 * `steward-ui` and "base url" find `base-url`, which is a spelling difference and not a different
 * word. A dot is *not* flattened - `roles.donor` is typed as `roles.donor` by anyone who means it.
 */
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
 * What one row scores against what was typed - 0 for "not a match at all", which is what hides it.
 *
 * The six steps are deliberately coarse, because the thing being ordered is a short list a person
 * reads top to bottom, not a relevance model: the whole name, the start of the name, a word of the
 * name, anywhere in the name, a word of the context, anywhere in the context. Rows that tie keep
 * the order their caller put them in, which for settings is file order.
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

/** The haystack a hit is ranked by - the same text each caller already hands the palette. */
export function hitValue(hit: SettingsHit): string {
  return hit.kind === "config" ? entryHaystack(hit.entry) : messageEntryHaystack(hit.entry, hit.language)
}

/**
 * Best first, ties in the order they came - so the thirty a caller keeps are the best thirty and
 * not the first thirty of a list in file order. `sort` is stable in every engine this runs on
 * (ECMAScript requires it since 2019), which is what makes "ties keep file order" a fact rather
 * than a hope.
 */
export function rankHits<T extends SettingsHit>(hits: T[], query: string): T[] {
  return [...hits].sort((a, b) => rankValue(hitValue(b), query) - rankValue(hitValue(a), query))
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

/**
 * The same subscriber set {@link onPendingMessageJump} keeps, and it is here for the same reason -
 * belatedly (steward/127).
 *
 * A config jump was read only by a component about to mount, which is right for "palette, then
 * navigate to another service" and wrong for the case Till found: searching while already standing
 * on the service page the hit belongs to. `navigate` to the route you are on is a no-op, nothing
 * remounts, the effect keyed on `service` does not run, and the click does nothing at all - and the
 * jump then sits in the map and fires the next time somebody arrives on that page, which is a
 * second wrong thing wearing the first one's clothes.
 */
const jumpSubscribers = new Set<() => void>()

export function setPendingJump(service: string, jump: PendingJump): void {
  pendingJumps.set(service, jump)
  jumpSubscribers.forEach((subscriber) => subscriber())
}

/**
 * Notifies {@code listener} every time any service's config jump is set. Filtering by service is
 * the caller's job, as with {@link onPendingMessageJump}.
 *
 * @returns the unsubscribe function, for a `useEffect` cleanup
 */
export function onPendingJump(listener: () => void): () => void {
  jumpSubscribers.add(listener)
  return () => jumpSubscribers.delete(listener)
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
 * often as it is read by one arriving fresh from a navigation: the Settings tab
 * (`ServiceSettings`, in `settings.tsx`) may already be open when the command palette picks a hit
 * on the same page, and then the navigation changes nothing that would remount it. So a jump has to reach a component
 * that may already be sitting there, not only one about to mount, which a plain map cannot do on
 * its own - nothing tells an already-rendered subscriber that a new entry showed up. `subscribers`
 * is the difference: `setPendingMessageJump` calls every one of them, and `ServiceSettings` both
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
