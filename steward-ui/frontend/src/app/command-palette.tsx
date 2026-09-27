import { ClockCounterClockwiseIcon, SlidersHorizontalIcon, TranslateIcon } from "@phosphor-icons/react"
import * as React from "react"
import { useNavigate } from "@tanstack/react-router"

import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
  CommandShortcut,
} from "@/components/ui/command"
import { NAVIGATION } from "@/app/navigation"
import { RUN_KIND_SEARCH_TERMS } from "@/app/run-search-terms"
import type { ConfigLocation, MessageBundleLocation, Run } from "@/lib/api"
import { runPath } from "@/lib/run-path"
import { dateTime, relative } from "@/lib/format"
import { useConfigDocuments, useConfigs, useMessageBundles, useMessageDocuments, useRuns } from "@/lib/queries"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import {
  entryHaystack,
  messageEntryHaystack,
  rankHits,
  rankValue,
  searchSettingsAndMessages,
  searchValue,
  setPendingJump,
  setPendingMessageJump,
} from "@/lib/settings-search"
import { bundleFileId } from "@/components/steward/settings"
import { messageName } from "@/lib/settings-tree"
import { RUN_KIND, RUN_STATUS } from "@/components/steward/status"

/** Four rows while the documents are read. */
const WAITING_HITS = [0, 1, 2, 3]

/** How many settings hits the palette shows, since cmdk draws every item it is handed. */
const MAX_SETTINGS_HITS = 30

/** A stable identity for "nothing loaded yet", so a memo keyed on it does not recompute every render. */
const NO_CONFIGS: ConfigLocation[] = []
const NO_BUNDLES: MessageBundleLocation[] = []

/**
 * A run's search text: number, kind, outcome, absolute time and the kind's extra search terms.
 *
 * `dateTime`, not `relative`, since a relative time would match different text from one render to the next.
 */
function runSearchValue(run: Run): string {
  return searchValue(
    `run #${run.id}`,
    [
      RUN_KIND[run.kind] ?? run.kind,
      RUN_STATUS[run.status] ?? run.status,
      dateTime(run.requested),
      ...(RUN_KIND_SEARCH_TERMS[run.kind] ?? []),
    ].join(" "),
  )
}

/**
 * The search dialog on Ctrl+K and ⌘K: every route, every run, and every setting and message.
 *
 * Runs, configs and bundles are only fetched while the dialog is open.
 */
export function CommandPalette() {
  const [open, setOpen] = React.useState(false)
  /** Controlled only so the Settings group knows what was typed; the other groups use cmdk's filter. */
  const [search, setSearch] = React.useState("")
  const navigate = useNavigate()
  const runs = useRuns(20, open).data ?? []

  /** Every config file's documents, fetched once the palette is open and something is typed. */
  const locations = useConfigs(open).data ?? NO_CONFIGS
  const paths = React.useMemo(() => locations.map((location) => location.path), [locations])
  const documents = useConfigDocuments(paths, open && search.trim().length > 0)

  /** The message bundles, the second supplier of the same search, gated the same way. */
  const bundleLocations = useMessageBundles(open).data ?? NO_BUNDLES
  const bundlePaths = React.useMemo(() => bundleLocations.map((location) => location.path), [bundleLocations])
  const bundleDocuments = useMessageDocuments(bundlePaths, open && search.trim().length > 0)

  /** While documents are still on the wire, the settings group shows a waiting shape, not "Nothing found.". */
  const reading =
    open &&
    search.trim().length > 0 &&
    (documents.some((query) => query.isPending) || bundleDocuments.some((query) => query.isPending))

  const settingsHits = React.useMemo(() => {
    if (!search.trim()) return []
    const configPairs = locations.map((location, index) => ({ location, document: documents[index]?.data }))
    const messagePairs = bundleLocations.map((location, index) => ({
      location,
      bundle: bundleDocuments[index]?.data,
    }))
    /** Ranked before `MAX_SETTINGS_HITS` cuts the list, so cmdk gets the best thirty, not the first. */
    return rankHits(searchSettingsAndMessages(configPairs, messagePairs, search), search)
  }, [locations, documents, bundleLocations, bundleDocuments, search])

  /** The listener is registered once, so it reads `open` through a ref. */
  const openRef = React.useRef(open)
  React.useEffect(() => {
    openRef.current = open
  }, [open])

  /** Clears a stale query on reopening, during render, since this component never unmounts. */
  const [wasOpen, setWasOpen] = React.useState(open)
  if (wasOpen !== open) {
    setWasOpen(open)
    if (!open) setSearch("")
  }

  React.useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== "k") return
      if (!event.metaKey && !event.ctrlKey) return
      /** A Ctrl+K typed into a field stays the browser's, except in the palette's own input, where it closes. */
      if (!openRef.current && isEditable(event.target)) return
      event.preventDefault()
      setOpen((previous) => !previous)
    }
    document.addEventListener("keydown", onKeyDown)
    return () => document.removeEventListener("keydown", onKeyDown)
  }, [])

  return (
    <CommandDialog
      open={open}
      onOpenChange={setOpen}
      title="Search"
      description="Jump to a page"
      label="Search pages, runs, settings"
      // A substring match ranking a name above a mention; the text is in `keywords`, as `value` is the identity.
      filter={(value, query, keywords) => rankValue(keywords?.join("\n") ?? value, query)}
      // The dialog half only: on a phone the sheet is the full width at the bottom edge.
      className="sm:top-[20%] sm:w-[calc(100%-2rem)] sm:max-w-2xl sm:translate-y-0"
    >
      <CommandInput
        /** A sheet does not move focus into itself as the dialog does, so the field takes it. */
        autoFocus
        value={search}
        onValueChange={setSearch}
        placeholder="Search pages, runs, settings…"
      />
      <CommandList className="max-h-[22rem]">
        <CommandEmpty>{reading ? "Still reading the settings…" : "Nothing found."}</CommandEmpty>
        {NAVIGATION.map((group, index) => (
          <React.Fragment key={group.id}>
            {index > 0 ? <CommandSeparator /> : null}
            <CommandGroup heading={group.label}>
              {group.entries.map((entry) => {
                const Icon = entry.icon ?? group.icon
                return (
                  <CommandItem
                    key={entry.id}
                    /** The label first, so a row named what was typed ranks above one that only mentions it. */
                    value={`page-${group.id}-${entry.id}`}
                    keywords={[
                      searchValue(
                        entry.label,
                        [group.label, entry.note, ...(entry.keywords ?? [])].filter(Boolean).join(" "),
                      ),
                    ]}
                    onSelect={() => {
                      setOpen(false)
                      void navigate({ to: entry.to, params: entry.params })
                    }}
                    className="min-h-control gap-2.5"
                  >
                    <Icon aria-hidden className="text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate">{entry.label}</span>
                    <CommandShortcut className="truncate text-muted-foreground/70">
                      {entry.params ? Object.values(entry.params).join(" ") : null}
                    </CommandShortcut>
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </React.Fragment>
        ))}
        {runs.length > 0 ? (
          <>
            <CommandSeparator />
            <CommandGroup heading="Runs">
              {runs.map((run) => (
                <CommandItem
                  key={`run-${run.id}`}
                  value={`run-${run.id}`}
                  keywords={[runSearchValue(run)]}
                  onSelect={() => {
                    setOpen(false)
                    void navigate(runPath(run))
                  }}
                  className="min-h-control gap-2.5"
                >
                  <ClockCounterClockwiseIcon aria-hidden className="text-muted-foreground" />
                  <span className="min-w-0 flex-1 truncate">
                    Run #{run.id} ({RUN_KIND[run.kind] ?? run.kind})
                  </span>
                  <CommandShortcut className="truncate text-muted-foreground/70">
                    {RUN_STATUS[run.status] ?? run.status} ({relative(run.requested)})
                  </CommandShortcut>
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        ) : null}
        {/* The settings search, only once something is typed, since the list is not a small fixed set. */}
        {/* Plain divs outside any group, since cmdk would filter a waiting `CommandItem` away. */}
        {reading && settingsHits.length === 0 ? (
          <>
            <CommandSeparator />
            <div className="p-1">
              <div className="px-2 py-1.5 text-xs font-medium text-muted-foreground">Settings</div>
              {WAITING_HITS.map((index) => (
                <div key={index} className="flex min-h-control items-center gap-2.5 px-2">
                  <Skeleton className="size-4 shrink-0 rounded-sm" />
                  <SkeletonText width="long" className="min-w-0 flex-1" />
                </div>
              ))}
            </div>
          </>
        ) : null}
        {settingsHits.length > 0 ? (
          <>
            <CommandSeparator />
            {/* Config and message hits together; either opens its file on the Settings & Translations tab. */}
            <CommandGroup heading="Settings">
              {settingsHits.slice(0, MAX_SETTINGS_HITS).map((hit) => {
                const service = hit.location.service || "steward-ui"
                if (hit.kind === "config") {
                  return (
                    <CommandItem
                      key={`setting-${hit.location.path}-${hit.entry.path}`}
                      value={`setting-${hit.location.path}-${hit.entry.path}`}
                      keywords={[entryHaystack(hit.entry)]}
                      onSelect={() => {
                        setOpen(false)
                        setPendingJump(service, { file: hit.location.path, path: hit.entry.path })
                        void navigate({
                          to: "/services/$name",
                          params: { name: service },
                          search: { tab: "settings", file: hit.location.path },
                        })
                      }}
                      className="min-h-control gap-2.5"
                    >
                      <SlidersHorizontalIcon aria-hidden className="text-muted-foreground" />
                      <span className="min-w-0 flex-1 truncate">{hit.entry.label}</span>
                      <HitDetail service={service} text={hit.entry.value} />
                    </CommandItem>
                  )
                }
                const text =
                  hit.language === "en"
                    ? (hit.entry.overrideEnglish ?? hit.entry.english)
                    : (hit.entry.overrideGerman ?? hit.entry.german)
                return (
                  <CommandItem
                    key={`message-${hit.location.path}-${hit.language}-${hit.entry.key}`}
                    value={`message-${hit.location.path}-${hit.language}-${hit.entry.key}`}
                    keywords={[messageEntryHaystack(hit.entry, hit.language)]}
                    onSelect={() => {
                      setOpen(false)
                      setPendingMessageJump(service, {
                        path: hit.location.path,
                        language: hit.language,
                        key: hit.entry.key,
                      })
                      void navigate({
                        to: "/services/$name",
                        params: { name: service },
                        search: { tab: "settings", file: bundleFileId(hit.location.path) },
                      })
                    }}
                    className="min-h-control gap-2.5"
                  >
                    <TranslateIcon aria-hidden className="text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate">{messageName(hit.entry)}</span>
                    <HitDetail service={service} text={text} />
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </>
        ) : null}
      </CommandList>
    </CommandDialog>
  )
}

/** The right-hand side of a settings hit: the service, then the value or text, cut short. */
function HitDetail({ service, text }: { service: string; text: string | null | undefined }) {
  const flat = (text ?? "").replace(/\s+/g, " ").trim()
  const short = flat.length > 40 ? `${flat.slice(0, 39)}…` : flat
  return (
    <CommandShortcut className="max-w-[45%] truncate text-xs tracking-normal text-muted-foreground max-sm:hidden">
      {service}
      {short ? ` ${short}` : null}
    </CommandShortcut>
  )
}

/** Whether the key went to something somebody is typing in. */
function isEditable(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)
}
