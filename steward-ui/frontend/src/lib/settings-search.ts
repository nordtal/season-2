import type { ConfigDocument, ConfigEntry, ConfigLocation } from "@/lib/api"

/**
 * Settings search (steward/58), in both the shapes the ticket asked for: a box confined to one
 * service's own files, and the command palette's search across every file the mount holds. Both
 * end up calling the functions below, so "which four things does a query match" is answered once
 * rather than twice - and so the one rule that actually matters, the one about a secret, is
 * enforced in exactly one place instead of trusted to be repeated correctly at every call site.
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

/** One hit: which file it lives in, and which of its entries matched. */
export type SettingsHit = {
  location: ConfigLocation
  entry: ConfigEntry
}

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
): SettingsHit[] {
  if (!query.trim()) return []
  const hits: SettingsHit[] = []
  for (const { location, document } of files) {
    if (!document || document.raw) continue
    for (const entry of document.entries) {
      if (matchesQuery(entry, query)) hits.push({ location, entry })
    }
  }
  return hits
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
