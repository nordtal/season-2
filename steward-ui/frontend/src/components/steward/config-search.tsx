import { useMemo, useState } from "react"
import { ChevronRight, Lock, Search } from "lucide-react"

import type { ConfigLocation } from "@/lib/api"
import { useConfigDocuments } from "@/lib/queries"
import { humanFileName } from "@/components/steward/config-controls"
import { searchAcross, type SettingsHit } from "@/lib/settings-search"
import { Badge } from "@/components/ui/badge"
import { Input } from "@/components/ui/input"

/**
 * Search within one service's own settings (steward/58's first box).
 *
 * Every file the service has is small in practice - the mount holds twenty-five in total, spread
 * over roughly a dozen services (steward/56's own count, 2026-09-14) - so all of them are fetched
 * once something is typed, the same way {@link import("@/components/steward/configuration").OneFile}
 * fetches one on its own. Nothing is fetched while the box is empty: an unopened search is a
 * request nobody made, the same rule the file list itself already follows.
 */
export function ServiceSettingsSearch({
  files,
  onJump,
}: {
  files: ConfigLocation[]
  onJump: (file: string, path: string) => void
}) {
  const [query, setQuery] = useState("")
  const trimmed = query.trim()
  const paths = useMemo(() => files.map((file) => file.path), [files])
  const documents = useConfigDocuments(paths, trimmed.length > 0)

  const hits = useMemo(() => {
    if (!trimmed) return []
    const pairs = files.map((location, index) => ({ location, document: documents[index]?.data }))
    return searchAcross(pairs, trimmed)
  }, [files, documents, trimmed])

  const loading = trimmed.length > 0 && documents.some((result) => result.isLoading)

  if (files.length === 0) return null

  return (
    <div className="flex flex-col gap-2 pb-3">
      <div className="flex items-center gap-2">
        <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
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
            <p className="px-2 py-1.5 text-sm text-muted-foreground">
              {loading ? "Searching…" : "Nothing found."}
            </p>
          ) : (
            hits.map((hit) => (
              <SettingsHitRow
                key={`${hit.location.path}:${hit.entry.path}`}
                hit={hit}
                onSelect={() => {
                  onJump(hit.location.path, hit.entry.path)
                  setQuery("")
                }}
              />
            ))
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
  const { location, entry } = hit
  // Never a secret's value, per `searchAcross`/`entryHaystack` - this only ever prints what was
  // already safe to match on, so there is nothing extra to guard here.
  const preview = !entry.secret ? entry.value || entry.items?.join(", ") : undefined

  return (
    <button
      type="button"
      onClick={onSelect}
      className="-mx-1 flex flex-col items-start gap-0.5 rounded-sm px-2 py-1.5 text-left hover:bg-accent"
    >
      <span className="flex w-full min-w-0 flex-wrap items-center gap-1.5">
        <span className="truncate text-sm">{entry.label}</span>
        {entry.secret ? (
          <Badge variant="outline" className="shrink-0 gap-1">
            <Lock className="size-3" aria-hidden />
            secret
          </Badge>
        ) : null}
      </span>
      {/* A path, not a sentence, so the same icon separator `Breadcrumb` defaults to - never a
          text symbol standing in for one (Till, 2026-09-16). */}
      <span className="flex w-full min-w-0 items-center gap-1 font-mono text-xs text-muted-foreground">
        {showService ? (
          <>
            <span className="shrink-0 truncate">{location.service || "steward-ui"}</span>
            <ChevronRight className="size-3 shrink-0" aria-hidden />
          </>
        ) : null}
        <span className="shrink-0 truncate">{humanFileName(location.name)}</span>
        <ChevronRight className="size-3 shrink-0" aria-hidden />
        <span className="min-w-0 flex-1 truncate">{entry.path}</span>
      </span>
      {preview ? (
        <span className="w-full truncate text-xs text-muted-foreground">{preview}</span>
      ) : null}
    </button>
  )
}
