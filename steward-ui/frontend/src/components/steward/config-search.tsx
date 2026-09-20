import { CaretRightIcon, LockIcon, MagnifyingGlassIcon, TranslateIcon } from "@phosphor-icons/react"
import { useMemo, useState } from "react"

import type { ConfigLocation } from "@/lib/api"
import { SkeletonText } from "@/components/steward/query-state"
import { useConfigDocuments, useMessageBundles, useMessageDocuments } from "@/lib/queries"
import { humanFileName } from "@/components/steward/config-controls"
import {
  rankHits,
  searchAcross,
  searchMessagesAcross,
  setPendingMessageJump,
  type SettingsHit,
} from "@/lib/settings-search"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"

/**
 * Search within one service's own settings (steward/58's first box), extended by steward/87 to
 * search that service's message bundles too - one list, both suppliers, same as the command
 * palette's global search.
 *
 * Every file the service has is small in practice - the mount holds twenty-five in total, spread
 * over roughly a dozen services (steward/56's own count, 2026-09-14) - so all of them are fetched
 * once something is typed, the same way {@link import("@/components/steward/configuration").OneFile}
 * fetches one on its own. Nothing is fetched while the box is empty: an unopened search is a
 * request nobody made, the same rule the file list itself already follows. Measured on this host,
 * 2026-09-17: five bundles hold between 282 and 429 keys each, so fetching every bundle a service
 * has the moment something is typed is the same bounded, cached cost `useConfigDocuments` already
 * accepted for config files - nowhere near the point a worker-side index would earn its keep.
 *
 * **`onJump` only ever fires for a config hit.** It is `configuration.tsx`'s own callback, closing
 * over that component's `open`/`highlight` state - a config file's identity, not a bundle's. A
 * message hit is handed to the messages tool instead, through `setPendingMessageJump`: `messages.tsx`
 * (`ServiceMessages`) is mounted right below `ServiceConfiguration` on the same page and subscribes
 * to that map for exactly this reason - a hit found here has nowhere to navigate *to*, both cards
 * are already on screen, so "jump" means "tell the other card", not "load a new page".
 */
/** Three rows while the documents are read - the list itself is short and usually shorter. */
const WAITING_HITS = [0, 1, 2]

export function ServiceSettingsSearch({
  files,
  onJump,
}: {
  files: ConfigLocation[]
  onJump: (file: string, path: string) => void
}) {
  const [query, setQuery] = useState("")
  const trimmed = query.trim()
  const searching = trimmed.length > 0

  const paths = useMemo(() => files.map((file) => file.path), [files])
  const documents = useConfigDocuments(paths, searching)

  // The service these files belong to, read off the files themselves rather than taken as a prop:
  // `configuration.tsx` is frozen for this ticket and its one call site does not pass one. Every
  // file in `files` already shares a service - `ServiceConfiguration` filters to exactly that
  // before handing them down - so the first one names it, with the same "no service" fallback
  // `ConfigLocation.service` itself uses for steward-ui's own loose files.
  const service = files[0]?.service || "steward-ui"
  const bundleLocations = useMessageBundles(searching).data ?? []
  const mine = useMemo(
    () => bundleLocations.filter((bundle) => bundle.service === service),
    [bundleLocations, service],
  )
  const bundlePaths = useMemo(() => mine.map((bundle) => bundle.path), [mine])
  const bundleDocuments = useMessageDocuments(bundlePaths, searching)

  const hits = useMemo(() => {
    if (!trimmed) return []
    const configPairs = files.map((location, index) => ({ location, document: documents[index]?.data }))
    const messagePairs = mine.map((location, index) => ({ location, bundle: bundleDocuments[index]?.data }))
    // Best first rather than file order (steward/105): `donation-cents` is written above
    // `roles.donor` in `discord-bot/access.yml` and its explanation mentions the donor role, so
    // typing "Donor" used to answer with the setting that merely talks about it. Nothing is
    // dropped here - `rankHits` only reorders what the two suppliers already matched.
    return rankHits(
      [...searchAcross(configPairs, trimmed), ...searchMessagesAcross(messagePairs, trimmed)],
      trimmed,
    )
  }, [files, documents, mine, bundleDocuments, trimmed])

  const loading =
    searching &&
    (documents.some((result) => result.isLoading) || bundleDocuments.some((result) => result.isLoading))

  if (files.length === 0) return null

  return (
    <div className="flex flex-col gap-2 pb-3">
      <div className="flex items-center gap-2">
        <MagnifyingGlassIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <Input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search this service's settings…"
          aria-label="Search this service's settings"
        />
      </div>
      {trimmed ? (
        <div className="flex flex-col gap-0.5 rounded-md border border-border p-1">
          {hits.length === 0 ? (
            // steward/120: "Searching…" was a sentence where rows were about to be, so the box
            // grew from one line to four under the pointer. Three rows in the shape of a hit is
            // the same promise without the jump - and "Nothing found." stays a sentence, because
            // an empty result is not a shape that is about to fill.
            loading ? (
              WAITING_HITS.map((index) => (
                <div key={index} className="flex min-h-control flex-col justify-center gap-1 px-2">
                  <SkeletonText width="long" className="text-sm" />
                  <SkeletonText width="medium" className="text-xs" />
                </div>
              ))
            ) : (
              <p className="px-2 py-1.5 text-sm text-muted-foreground">Nothing found.</p>
            )
          ) : (
            hits.map((hit) =>
              hit.kind === "config" ? (
                <SettingsHitRow
                  key={`config:${hit.location.path}:${hit.entry.path}`}
                  hit={hit}
                  onSelect={() => {
                    onJump(hit.location.path, hit.entry.path)
                    setQuery("")
                  }}
                />
              ) : (
                <SettingsHitRow
                  key={`message:${hit.location.path}:${hit.language}:${hit.entry.key}`}
                  hit={hit}
                  onSelect={() => {
                    setPendingMessageJump(service, {
                      path: hit.location.path,
                      language: hit.language,
                      key: hit.entry.key,
                    })
                    setQuery("")
                  }}
                />
              ),
            )
          )}
        </div>
      ) : null}
    </div>
  )
}

/**
 * One search result - shared shape between the per-service box above and, indirectly, the
 * command palette's global search, which draws its own rows in cmdk's own item component instead
 * of this one, but reads the same `SettingsHit` and formats the file name the same way.
 *
 * steward/87: a hit's location is three parts for a config file (service / file / key) and four
 * for a bundle (service / module / language / key) - "the place display has to catch up", per the
 * ticket. Both are drawn as the same breadcrumb of `ChevronRight`-separated segments (never a text
 * symbol standing in for one, Till 2026-09-16); a bundle's row simply has one more of them, with
 * `EN`/`DE` standing in for the file segment a config row shows instead. Till took the plain list
 * over separate config/message groups (steward/87's own choice, offered and declined) - if a
 * four-part row next to a three-part one reads as unbalanced on a phone once this is on screen,
 * that is a finding for him, not a reason to split the list back apart unasked.
 */
export function SettingsHitRow({
  hit,
  onSelect,
  showService = false,
}: {
  hit: SettingsHit
  onSelect: () => void
  showService?: boolean
}) {
  // `hit.location` on purpose, not a destructured `location` - `name` and `module` are not common
  // to both members of the union, and only accessing them through the still-narrowed `hit` lets
  // TypeScript follow the `hit.kind` check into which one is there.
  //
  // A message hit's main label is the matched *text* for this hit's language (the operator's
  // override, or the packaged default), never `entry.key` - the key is already the last segment of
  // the breadcrumb below, and a config hit already keeps the same split (`entry.label`, a human
  // name, above; `entry.path`, the technical identifier, in the breadcrumb). Printing the key in
  // both places drew the same word twice in one row and made `findByText`/`getByText` ambiguous
  // (steward/87, found while writing this box's own tests: two identical `<span>` nodes in a row
  // that legitimately has only one hit). Falling back to the key only covers a bundle entry with no
  // text in this language at all, which `searchMessagesAcross` already excludes from ever
  // producing a hit - the fallback exists so this function stays correct on its own, not because it
  // is reachable today.
  const messageText =
    hit.kind === "message"
      ? (hit.language === "en"
          ? (hit.entry.overrideEnglish ?? hit.entry.english)
          : (hit.entry.overrideGerman ?? hit.entry.german)) ?? hit.entry.key
      : undefined
  const label = hit.kind === "config" ? hit.entry.label : messageText
  // Never a secret's value, per `searchAcross`/`entryHaystack` - this only ever prints what was
  // already safe to match on, so there is nothing extra to guard here. A message hit has no second
  // line of its own left to show - its matched text is already the main label above, and repeating
  // it here would be the same duplication a third time - so it carries no preview at all.
  const preview =
    hit.kind === "config" && !hit.entry.secret
      ? hit.entry.value || hit.entry.items?.join(", ")
      : undefined

  return (
    <button
      type="button"
      onClick={onSelect}
      className="-mx-1 flex flex-col items-start gap-0.5 rounded-sm px-2 py-1.5 text-left hover:bg-accent"
    >
      <span className="flex w-full min-w-0 flex-wrap items-center gap-1.5">
        <span className="truncate text-sm">{label}</span>
        {hit.kind === "config" && hit.entry.secret ? (
          <Badge variant="outline" className="shrink-0 gap-1">
            <LockIcon className="size-3" aria-hidden />
            secret
          </Badge>
        ) : null}
      </span>
      {/* A path, not a sentence, so the same icon separator `Breadcrumb` defaults to - never a
          text symbol standing in for one (Till, 2026-09-16). */}
      <span className="flex w-full min-w-0 items-center gap-1 font-mono text-xs text-muted-foreground">
        {showService ? (
          <>
            <span className="shrink-0 truncate">{hit.location.service || "steward-ui"}</span>
            <CaretRightIcon className="size-3 shrink-0" aria-hidden />
          </>
        ) : null}
        {hit.kind === "config" ? (
          <>
            <span className="shrink-0 truncate">{humanFileName(hit.location.name)}</span>
            <CaretRightIcon className="size-3 shrink-0" aria-hidden />
            <span className="min-w-0 flex-1 truncate">{hit.entry.path}</span>
          </>
        ) : (
          <>
            {hit.location.module ? (
              <>
                <span className="shrink-0 truncate">{hit.location.module}</span>
                <CaretRightIcon className="size-3 shrink-0" aria-hidden />
              </>
            ) : null}
            <TranslateIcon className="size-3 shrink-0" aria-hidden />
            <span className="shrink-0 truncate">{hit.language === "en" ? "EN" : "DE"}</span>
            <CaretRightIcon className="size-3 shrink-0" aria-hidden />
            <span className="min-w-0 flex-1 truncate">{hit.entry.key}</span>
          </>
        )}
      </span>
      {preview ? (
        <span className="w-full truncate text-xs text-muted-foreground">{preview}</span>
      ) : null}
    </button>
  )
}
