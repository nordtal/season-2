import { ClockCounterClockwiseIcon, GearSixIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
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
import { useNavigation } from "@/app/navigation"
import { RUN_KIND_SEARCH_TERMS } from "@/app/run-search-terms"
import type { ConfigLocation, MessageText, PluginFile, Run, Service } from "@/lib/api"
import { runPath } from "@/lib/run-path"
import { dateTime } from "@/lib/format"
import { useConfigDocuments, useConfigs, useMessageTexts, usePluginFilesOf, useRuns, useServices } from "@/lib/queries"
import { Skeleton, SkeletonText } from "@/components/steward/query-state"
import {
  entryHaystack,
  messageEntryHaystack,
  rankHits,
  rankValue,
  searchSettingsAndMessages,
  searchValue,
  setPendingJump,
  setPendingTextJump,
} from "@/lib/settings-search"
import { NETWORK } from "@/components/steward/settings"
import { messageName, pluginFileId, pluginLabel, textId } from "@/lib/settings-tree"
import { shownOf } from "@/lib/message-text"
import { runKind, runStatus } from "@/components/steward/status"
import { t } from "@/lib/texts"

const WAITING_HITS = [0, 1, 2, 3]

const MAX_SETTINGS_HITS = 30

const NO_CONFIGS: ConfigLocation[] = []
const NO_TEXTS: MessageText[] = []
const NO_SERVICES: Service[] = []

/** A plugin file is found by its plugin's name and its path, and by its service after them. */
function pluginFileValue(file: PluginFile): string {
  return searchValue(`${pluginLabel(file)} ${file.path}`, file.service)
}

/**
 * Returns the search text of a run. It uses `dateTime`, because a `relative` time changes between renders.
 */
function runSearchValue(run: Run): string {
  return searchValue(
    `run #${run.id}`,
    [
      runKind(run.kind),
      runStatus(run.status),
      dateTime(run.requested),
      ...(RUN_KIND_SEARCH_TERMS[run.kind] ?? []),
    ].join(" "),
  )
}

/**
 * The search dialog on Ctrl+K and ⌘K. It fetches runs, configs and texts only while it is open.
 */
export function CommandPalette() {
  const [open, setOpen] = React.useState(false)
  /**
   * The palette controls the search state itself, because the Settings group needs the typed text. The other groups use cmdk's filter.
   */
  const [search, setSearch] = React.useState("")
  const navigate = useNavigate()
  const groups = useNavigation()
  const runs = useRuns(20, open).data ?? []

  const locations = useConfigs(open).data ?? NO_CONFIGS
  const paths = React.useMemo(() => locations.map((location) => location.path), [locations])
  const documents = useConfigDocuments(paths, open && search.trim().length > 0)

  const textsQuery = useMessageTexts(open && search.trim().length > 0)
  const texts = textsQuery.data?.texts ?? NO_TEXTS

  const services = useServices(open && search.trim().length > 0).data?.services ?? NO_SERVICES
  const withPlugins = React.useMemo(
    () => services.filter((service) => service.hasPlugins).map((service) => service.service),
    [services],
  )
  const pluginFiles = usePluginFilesOf(withPlugins, open && search.trim().length > 0)

  const reading =
    open &&
    search.trim().length > 0 &&
    (documents.some((query) => query.isPending) || textsQuery.isPending || pluginFiles.some((query) => query.isPending))

  const settingsHits = React.useMemo(() => {
    if (!search.trim()) return []
    const configPairs = locations.map((location, index) => ({ location, document: documents[index]?.data }))
    return rankHits(searchSettingsAndMessages(configPairs, texts, search), search)
  }, [locations, documents, texts, search])

  const pluginHits = React.useMemo(
    () =>
      search.trim()
        ? pluginFiles
            .flatMap((query) => query.data?.files ?? [])
            .filter((file) => file.readable && rankValue(pluginFileValue(file), search) > 0)
        : [],
    [pluginFiles, search],
  )

  /** The key listener is registered once, so it reads `open` through a ref. */
  const openRef = React.useRef(open)
  React.useEffect(() => {
    openRef.current = open
  }, [open])

  /** Clears the old query on reopening during render, because the component never unmounts. */
  const [wasOpen, setWasOpen] = React.useState(open)
  if (wasOpen !== open) {
    setWasOpen(open)
    if (!open) setSearch("")
  }

  React.useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key.toLowerCase() !== "k") return
      if (!event.metaKey && !event.ctrlKey) return
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
      title={t("steward.shell.search")}
      description={t("steward.shell.jump")}
      label={t("steward.shell.search-label")}
      // Ranks a name above a mention. The text is in `keywords`, because `value` is the row's identity.
      filter={(value, query, keywords) => rankValue(keywords?.join("\n") ?? value, query)}
      // Applies to the dialog only. On a phone the sheet is full width at the bottom edge.
      className="sm:top-[20%] sm:w-[calc(100%-2rem)] sm:max-w-2xl sm:translate-y-0"
    >
      <CommandInput
        autoFocus
        value={search}
        onValueChange={setSearch}
        placeholder={t("steward.shell.search-placeholder")}
      />
      <CommandList className="max-h-[22rem]">
        <CommandEmpty>{reading ? t("steward.shell.still-reading") : t("steward.shell.nothing-found")}</CommandEmpty>
        {groups.map((group, index) => (
          <React.Fragment key={group.id}>
            {index > 0 ? <CommandSeparator /> : null}
            <CommandGroup heading={group.label}>
              {group.entries.map((entry) => {
                const Icon = entry.icon
                return (
                  <CommandItem
                    key={entry.id}
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
                  </CommandItem>
                )
              })}
            </CommandGroup>
          </React.Fragment>
        ))}
        {runs.length > 0 ? (
          <>
            <CommandSeparator />
            <CommandGroup heading={t("steward.shell.runs")}>
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
                    {t("steward.shell.run-hit", { run: run.id, kind: runKind(run.kind) })}
                  </span>
                  <CommandShortcut className="truncate text-muted-foreground/70">
                    {t("steward.shell.run-when", { status: runStatus(run.status), requested: run.requested })}
                  </CommandShortcut>
                </CommandItem>
              ))}
            </CommandGroup>
          </>
        ) : null}
        {/* These are plain divs outside any group, because cmdk filters out a placeholder `CommandItem`. */}
        {reading && settingsHits.length === 0 && pluginHits.length === 0 ? (
          <>
            <CommandSeparator />
            <div className="p-1">
              <div className="px-2 py-1.5 text-xs font-medium text-muted-foreground">{t("steward.shell.settings")}</div>
              {WAITING_HITS.map((index) => (
                <div key={index} className="flex min-h-control items-center gap-2.5 px-2">
                  <Skeleton className="size-4 shrink-0 rounded-sm" />
                  <SkeletonText width="long" className="min-w-0 flex-1" />
                </div>
              ))}
            </div>
          </>
        ) : null}
        {settingsHits.length > 0 || pluginHits.length > 0 ? (
          <>
            <CommandSeparator />
            {/* Settings hits, plugin files and text hits share one group. */}
            <CommandGroup heading={t("steward.shell.settings")}>
              {pluginHits.slice(0, MAX_SETTINGS_HITS).map((file) => (
                <CommandItem
                  key={`plugin-file-${file.service}-${pluginFileId(file)}`}
                  value={`plugin-file-${file.service}-${pluginFileId(file)}`}
                  keywords={[pluginFileValue(file)]}
                  onSelect={() => {
                    setOpen(false)
                    void navigate({
                      to: "/services/$name",
                      params: { name: file.service },
                      search: { tab: "settings", file: pluginFileId(file) },
                    })
                  }}
                  className="min-h-control gap-2.5"
                >
                  <GearSixIcon aria-hidden className="text-muted-foreground" />
                  <span className="min-w-0 flex-1 truncate">{pluginLabel(file)}</span>
                  <HitDetail where={file.service} text={file.path} />
                </CommandItem>
              ))}
              {settingsHits.slice(0, MAX_SETTINGS_HITS).map((hit) => {
                if (hit.kind === "config") {
                  const service = hit.location.service || "steward"
                  return (
                    <CommandItem
                      key={`setting-${hit.location.path}-${hit.entry.path}`}
                      value={`setting-${hit.location.path}-${hit.entry.path}`}
                      keywords={[entryHaystack(hit.entry)]}
                      onSelect={() => {
                        setOpen(false)
                        setPendingJump(service, { file: hit.location.path, path: hit.entry.path })
                        void (service === NETWORK
                          ? navigate({ to: "/season", search: { file: hit.location.path } })
                          : navigate({
                              to: "/services/$name",
                              params: { name: service },
                              search: { tab: "settings", file: hit.location.path },
                            }))
                      }}
                      className="min-h-control gap-2.5"
                    >
                      <TERM_ICON.settings aria-hidden className="text-muted-foreground" />
                      <span className="min-w-0 flex-1 truncate">{hit.entry.label}</span>
                      <HitDetail where={service} text={hit.entry.value} />
                    </CommandItem>
                  )
                }
                const entry = hit.text.entry
                const id = textId(entry)
                return (
                  <CommandItem
                    key={`message-${id}-${hit.language}`}
                    value={`message-${id}-${hit.language}`}
                    keywords={[messageEntryHaystack(entry, hit.language)]}
                    onSelect={() => {
                      setOpen(false)
                      setPendingTextJump({ id, language: hit.language })
                      void navigate({ to: "/translations", search: {} })
                    }}
                    className="min-h-control gap-2.5"
                  >
                    <TERM_ICON.translations aria-hidden className="text-muted-foreground" />
                    <span className="min-w-0 flex-1 truncate">{messageName(entry)}</span>
                    <HitDetail where={entry.bundle} text={shownOf(entry, hit.language)[0]} />
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

function HitDetail({ where, text }: { where: string; text: string | null | undefined }) {
  const flat = (text ?? "").replace(/\s+/g, " ").trim()
  const short = flat.length > 40 ? `${flat.slice(0, 39)}…` : flat
  return (
    <CommandShortcut className="max-w-[45%] truncate text-xs tracking-normal text-muted-foreground max-sm:hidden">
      {where}
      {short ? ` ${short}` : null}
    </CommandShortcut>
  )
}

function isEditable(target: EventTarget | null) {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  return ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)
}
