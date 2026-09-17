import * as React from "react"
import { useNavigate } from "@tanstack/react-router"
import { History, Languages, SlidersHorizontal } from "lucide-react"

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
import type { Run } from "@/lib/api"
import { dateTime, relative } from "@/lib/format"
import {
  useConfigDocuments,
  useConfigs,
  useMessageBundles,
  useMessageDocuments,
  useRuns,
} from "@/lib/queries"
import {
  entryHaystack,
  messageEntryHaystack,
  searchSettingsAndMessages,
  setPendingJump,
  setPendingMessageJump,
} from "@/lib/settings-search"
import { humanFileName } from "@/components/steward/config-controls"
import { RUN_KIND, RUN_STATUS } from "@/components/steward/status"

/**
 * How many settings hits the palette ever shows at once (steward/58).
 *
 * Not a correctness limit - `searchAcross` itself returns every match - but a screen of thirty rows
 * is already more than anyone reads before narrowing the query further, and cmdk draws every item
 * it is handed whether or not it fits the visible list.
 */
const MAX_SETTINGS_HITS = 30

/**
 * A run's own search text.
 *
 * steward/52: a run has four things a person would search it by - its number, its kind, its
 * outcome and a time - and none of them is a page title, which is what the palette searched
 * before this. `dateTime` rather than `relative` for the time: `relative` reads differently on
 * every render ("3 minutes ago" becomes "4 minutes ago"), which would make the same run match a
 * different typed time from one moment to the next. `RUN_KIND_SEARCH_TERMS` adds the words a
 * person would type that are not the kind's own name - German among them, because Till does not
 * always type English. See that file for why one of those words is German rather than English.
 */
function runSearchValue(run: Run): string {
  return [
    `run #${run.id}`,
    RUN_KIND[run.kind] ?? run.kind,
    RUN_STATUS[run.status] ?? run.status,
    dateTime(run.requested),
    ...(RUN_KIND_SEARCH_TERMS[run.kind] ?? []),
  ].join(" ")
}

/**
 * Ctrl+K, and ⌘K on a Mac - both are listened for, always, so the label the interface prints is
 * a courtesy and never a condition. Every route in the interface is in here, including the parameterised
 * ones, which appear with a representative parameter - the point of the palette is that a place
 * you know the name of is one keystroke away, and "Run" is a name somebody knows.
 *
 * Runs themselves are the other half, added for steward/52: `useRuns` is only enabled while the
 * dialog is open, so a palette nobody has opened costs nothing beyond the one poll a page that is
 * already open may be running anyway.
 */
export function CommandPalette() {
  const [open, setOpen] = React.useState(false)
  // Controlled rather than left to cmdk's own state, only so the Settings group below (steward/58)
  // knows what has actually been typed - the Pages and Runs groups above still go through cmdk's
  // own fuzzy filter over each item's `value`, unaffected by this.
  const [search, setSearch] = React.useState("")
  const navigate = useNavigate()
  const runs = useRuns(20, open).data ?? []

  // Every config file's location, and then every one of their documents - both gated on `open` the
  // same way `runs` is, and the documents a second time on there being anything typed (steward/58's
  // fallback clause, for if this ever turns out too slow): the mount held twenty-five files in
  // total on 2026-09-14, over roughly a dozen services, so fetching all of them once somebody opens
  // the palette and starts typing is a bounded, cached cost rather than a reason to stand up a
  // worker-side index for this ticket.
  const locations = useConfigs(open).data ?? []
  const paths = React.useMemo(() => locations.map((location) => location.path), [locations])
  const documents = useConfigDocuments(paths, open && search.trim().length > 0)

  // steward/87: the message bundles are a second supplier for the same search, gated on `open` and
  // on something being typed the same way the config side already is - five bundles measured on
  // this host on 2026-09-17, 282 to 429 keys each, which is the same "small enough to just fetch
  // it" case `useConfigDocuments` already made for config files.
  const bundleLocations = useMessageBundles(open).data ?? []
  const bundlePaths = React.useMemo(
    () => bundleLocations.map((location) => location.path),
    [bundleLocations],
  )
  const bundleDocuments = useMessageDocuments(bundlePaths, open && search.trim().length > 0)

  const settingsHits = React.useMemo(() => {
    if (!search.trim()) return []
    const configPairs = locations.map((location, index) => ({ location, document: documents[index]?.data }))
    const messagePairs = bundleLocations.map((location, index) => ({
      location,
      bundle: bundleDocuments[index]?.data,
    }))
    return searchSettingsAndMessages(configPairs, messagePairs, search)
  }, [locations, documents, bundleLocations, bundleDocuments, search])

  // The listener is registered once, so it would otherwise read the `open` of the render it was
  // created in - which is always false.
  const openRef = React.useRef(open)
  openRef.current = open

  // A stale query from the last time this was open would otherwise sit in `search` (this component
  // never unmounts, only the dialog's own content does) and gate the Settings group on nothing the
  // reopened box actually shows.
  React.useEffect(() => {
    if (!open) setSearch("")
  }, [open])

  React.useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== "k") return
      if (!event.metaKey && !event.ctrlKey) return
      // Let the browser keep its own Ctrl+K when the user is typing into something. The comment
      // said so and the code did the opposite: every Ctrl+K in a console line or a config field
      // was swallowed and opened the search instead. The palette's own input is the exception -
      // there the shortcut is how you close it again.
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
      className="top-[20%] translate-y-0"
    >
      <CommandInput
        value={search}
        onValueChange={setSearch}
        placeholder="Search pages, runs, settings…"
      />
      <CommandList className="max-h-[22rem]">
        <CommandEmpty>Nothing found.</CommandEmpty>
        {NAVIGATION.map((group, index) => (
          <React.Fragment key={group.id}>
            {index > 0 ? <CommandSeparator /> : null}
            <CommandGroup heading={group.label}>
              {group.entries.map((entry) => {
                const Icon = entry.icon ?? group.icon
                return (
                  <CommandItem
                    key={entry.id}
                    value={[entry.label, group.label, entry.note, ...(entry.keywords ?? [])]
                      .filter(Boolean)
                      .join(" ")}
                    onSelect={() => {
                      setOpen(false)
                      void navigate({ to: entry.to, params: entry.params as never })
                    }}
                    className="min-h-control gap-2.5"
                  >
                    <Icon aria-hidden className="text-muted-foreground" />
                    <span className="truncate">{entry.label}</span>
                    <CommandShortcut className="truncate text-muted-foreground/70">
                      {entry.params
                        ? Object.values(entry.params).join(" ")
                        : null}
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
                  value={runSearchValue(run)}
                  onSelect={() => {
                    setOpen(false)
                    void navigate({
                      to: "/operations/runs/$id",
                      params: { id: String(run.id) },
                    })
                  }}
                  className="min-h-control gap-2.5"
                >
                  <History aria-hidden className="text-muted-foreground" />
                  <span className="truncate">
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
        {/*
          steward/58: search over the settings, across every service, with the service in the hit.
          Only drawn once something is typed - unlike Pages and Runs, the settings list is not a
          small fixed set, so an empty query would mean handing cmdk hundreds of rows to filter for
          nothing anyone asked to see yet.
        */}
        {settingsHits.length > 0 ? (
          <>
            <CommandSeparator />
            {/*
              steward/87: config hits and message-bundle hits in the one group, in the order
              `searchSettingsAndMessages` returned them - one list, not two groups, was
              Till's own choice when offered the alternative. A bundle hit's destination is the
              messages tool on the same service page, not the configuration form, so it goes
              through `setPendingMessageJump` rather than `setPendingJump` - the two are separate
              maps read by two different components, see `settings-search.ts`.
            */}
            <CommandGroup heading="Settings">
              {settingsHits.slice(0, MAX_SETTINGS_HITS).map((hit) => {
                const service = hit.location.service || "steward-ui"
                if (hit.kind === "config") {
                  return (
                    <CommandItem
                      key={`setting-${hit.location.path}-${hit.entry.path}`}
                      value={entryHaystack(hit.entry)}
                      onSelect={() => {
                        setOpen(false)
                        setPendingJump(service, { file: hit.location.path, path: hit.entry.path })
                        void navigate({ to: "/services/$name", params: { name: service } })
                      }}
                      className="min-h-control gap-2.5"
                    >
                      <SlidersHorizontal aria-hidden className="text-muted-foreground" />
                      <span className="min-w-0 flex-1 truncate">{hit.entry.label}</span>
                      <CommandShortcut className="truncate text-muted-foreground/70">
                        {humanFileName(hit.location.name)} ({service})
                      </CommandShortcut>
                    </CommandItem>
                  )
                }
                const bundleLabel = hit.location.module || hit.location.service
                const language = hit.language === "en" ? "EN" : "DE"
                // The matched text, not the raw key, is the main label - the same reasoning as
                // `SettingsHitRow` in `config-search.tsx` (steward/87): it mirrors a config hit's
                // own split of a human-facing label up front and the technical identifier tucked
                // into the metadata instead, and it is what fixed a row showing the same key twice.
                // `searchMessagesAcross` never produces a hit for a language with neither an
                // override nor packaged text, so the key fallback below is unreachable today, kept
                // only so this stays correct on its own.
                const messageLabel =
                  (hit.language === "en"
                    ? (hit.entry.overrideEnglish ?? hit.entry.english)
                    : (hit.entry.overrideGerman ?? hit.entry.german)) ?? hit.entry.key
                return (
                  <CommandItem
                    key={`message-${hit.location.path}-${hit.language}-${hit.entry.key}`}
                    value={messageEntryHaystack(hit.entry, hit.language)}
                    onSelect={() => {
                      setOpen(false)
                      setPendingMessageJump(service, {
                        path: hit.location.path,
                        language: hit.language,
                        key: hit.entry.key,
                      })
                      void navigate({ to: "/services/$name", params: { name: service } })
                    }}
                    className="min-h-control gap-2.5"
                  >
                    <Languages aria-hidden className="text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate">{messageLabel}</span>
                    <CommandShortcut className="truncate text-muted-foreground/70">
                      {bundleLabel} ({service}) {language} {hit.entry.key}
                    </CommandShortcut>
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

/** Whether the key went to something somebody is typing in. */
function isEditable(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)
}
