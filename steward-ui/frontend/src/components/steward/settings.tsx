import {
  ArrowCounterClockwiseIcon,
  ArrowLeftIcon,
  ArrowUpIcon,
  CaretRightIcon,
  EraserIcon,
  GearSixIcon,
  LockIcon,
  MagnifyingGlassIcon,
  TranslateIcon,
} from "@phosphor-icons/react"
import { Fragment, useEffect, useMemo, useRef, useState } from "react"
import type { CSSProperties, ReactNode } from "react"
import { cn } from "cn"

import type {
  ConfigEntry,
  ConfigLocation,
  GuildList,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
  ParsedConfigDocument,
  ReloadAwareConfigDocument,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, setOpened, useDirtyFiles, useDraft, useOpened } from "@/lib/drafts"
import {
  overrideOf,
  packagedOf,
  tokenOf,
  unknownPlaceholders,
  type Language,
} from "@/lib/message-text"
import {
  useConfig,
  useConfigs,
  useGuildChannels,
  useGuildRoles,
  useMessageBundle,
  useMessageBundles,
  useSaveConfig,
  useSaveMessageBundle,
} from "@/lib/queries"
import {
  onPendingJump,
  onPendingMessageJump,
  takePendingJump,
  takePendingMessageJump,
} from "@/lib/settings-search"
import {
  ancestorsOf,
  configLeafMatches,
  configTree,
  filterTree,
  idsBelow,
  leafCount,
  messageLeafMatches,
  messageName,
  messageTree,
  type ConfigLeafValue,
  type TreeBranch,
  type TreeLeaf,
  type TreeNode,
} from "@/lib/settings-tree"
import {
  changed,
  Control,
  EnvironmentOverriddenBadge,
  NotInSchemaBadge,
  RawConfigView,
  type Draft,
} from "@/components/steward/configuration"
import { explanationOf } from "@/components/steward/config-controls"
import { configTitle, translationsTitle } from "@/lib/words"
import type { PairedBlocks } from "@/components/steward/paired-blocks"
import { Failure, QueryState, SkeletonText } from "@/components/steward/query-state"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  InputGroup,
  InputGroupAddon,
  InputGroupButton,
  InputGroupInput,
  InputGroupTextarea,
} from "@/components/ui/input-group"
import { Label } from "@/components/ui/label"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"

/**
 * The Settings & Translations tab of a service page: every config file and every message bundle of
 * the service in one list, and the chosen one as a tree of named sections.
 *
 * Which file is open is the page's `?file=` - a config file by its path, a bundle by
 * {@link bundleFileId} - so it is handed in and reported back rather than kept here.
 */
export function ServiceSettings({
  service,
  file,
  onFile,
}: {
  service: string
  file: string | undefined
  /** `replace` for a choice nobody made, so Back does not return to a list that chose itself. */
  onFile: (file: string | undefined, replace?: boolean) => void
}) {
  const configs = useConfigs()
  const bundles = useMessageBundles()
  const dirty = useDirtyFiles()
  const wide = useWide()
  const [target, setTarget] = useState<Target | null>(null)
  const report = useRef(onFile)
  report.current = onFile

  const groups = useMemo(
    () => groupsOf(service, configs.data ?? [], bundles.data ?? []),
    [service, configs.data, bundles.data],
  )
  const files = useMemo(() => groups.flatMap((group) => group.items), [groups])
  const loading = configs.isPending || bundles.isPending
  // On a wide screen the content column is never empty: without a chosen file it shows the first
  // one. That is derived, not written into the URL - a write from here would still run in the
  // render that leaves the tab and put it straight back.
  const shown = file ?? (wide ? files.find((item) => item.readable)?.id : undefined)
  const selected = files.find((item) => item.id === shown)

  // A jump from the command palette, whether it arrived with the navigation or while this page was
  // already open. Each one gets a new number, so the same hit twice lands twice.
  useEffect(() => {
    const land = () => {
      const config = takePendingJump(service)
      if (config) {
        setTarget({ file: config.file, id: config.path, seq: ++jumps })
        report.current(config.file)
      }
      const message = takePendingMessageJump(service)
      if (message) {
        const id = bundleFileId(message.path)
        setTarget({ file: id, id: message.key, language: message.language, seq: ++jumps })
        report.current(id)
      }
    }
    land()
    const stopConfig = onPendingJump(land)
    const stopMessage = onPendingMessageJump(land)
    return () => {
      stopConfig()
      stopMessage()
    }
  }, [service])

  const failure = configs.error ?? bundles.error

  return (
    <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[15rem_minmax(0,1fr)] lg:items-start lg:gap-8">
      <nav aria-label="Files" className={cn("flex flex-col gap-0.5 lg:sticky lg:top-4", file !== undefined && "max-lg:hidden")}>
        {failure ? <Failure error={failure} /> : null}
        {loading
          ? [0, 1, 2].map((index) => (
              <div key={index} className="flex h-9 items-center gap-2 px-2">
                <SkeletonText className="text-sm" width="medium" />
              </div>
            ))
          : files.length === 0 && !failure
            ? <p className="px-2 text-sm text-muted-foreground">No files.</p>
            : groups.map((group) => (
                <Fragment key={group.label ?? "files"}>
                  {group.label ? (
                    <h3 className="px-2 pt-3 pb-1 text-xs font-medium text-muted-foreground first:pt-0">{group.label}</h3>
                  ) : null}
                  {group.items.map((item) => (
                    <FileRow
                      key={item.id}
                      item={item}
                      selected={item.id === shown}
                      dirty={dirty.includes(item.id)}
                      onSelect={() => onFile(item.id)}
                    />
                  ))}
                </Fragment>
              ))}
      </nav>

      <div className={cn("min-w-0", file === undefined && "max-lg:hidden")}>
        {selected ? (
          <>
            <div className="-ml-2 mb-2 flex h-9 items-center gap-1 lg:hidden">
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Back to the files"
                onClick={() => {
                  const index = (window.history.state as { __TSR_index?: number } | null)?.__TSR_index ?? 0
                  if (index > 0) window.history.back()
                  else onFile(undefined)
                }}
              >
                <ArrowLeftIcon aria-hidden />
              </Button>
              <span className="min-w-0 truncate text-sm font-medium">{selected.label}</span>
            </div>
            {selected.kind === "config" ? (
              <ConfigFile key={selected.id} item={selected} target={target} />
            ) : (
              <BundleFile key={selected.id} item={selected} target={target} />
            )}
          </>
        ) : file !== undefined && !loading ? (
          <p className="text-sm text-muted-foreground">No such file.</p>
        ) : null}
      </div>
    </div>
  )
}

/** Where a bundle is in `?file=`: its path would be a config file's otherwise. */
export function bundleFileId(path: string): string {
  return `bundle:${path}`
}

/** A counter, not state: the number only has to differ from the last one. */
let jumps = 0

type Target = { file: string; id: string; language?: Language; seq: number }

type FileItem =
  | { kind: "config"; id: string; label: string; readable: boolean; writable: boolean; location: ConfigLocation }
  | { kind: "bundle"; id: string; label: string; readable: boolean; writable: boolean; location: MessageBundleLocation }

type FileGroup = { label: string | null; items: FileItem[] }

/**
 * Nordtal's files first - its translations, then its configs - and everything a third-party plugin
 * wrote below them, by the same Nordtal set the plugins tab uses. The headings only appear when
 * there is a third-party group to tell apart.
 */
function groupsOf(service: string, configs: ConfigLocation[], bundles: MessageBundleLocation[]): FileGroup[] {
  const byLabel = (a: FileItem, b: FileItem) => a.label.localeCompare(b.label)
  const settings: FileItem[] = configs
    .filter((file) => file.service === service || (file.service === "" && service === "steward-ui"))
    .map((location) => ({
      kind: "config",
      id: location.path,
      label: configTitle(location),
      readable: location.readable,
      writable: location.writable,
      location,
    }))
  const translations: FileItem[] = bundles
    .filter((bundle) => bundle.service === service)
    .map((location) => ({
      kind: "bundle",
      id: bundleFileId(location.path),
      label: translationsTitle(location),
      readable: true,
      writable: location.writable,
      location,
    }))
  const thirdParty = (item: FileItem) => item.kind === "config" && item.location.origin === "third-party"
  const nordtal = [...translations.sort(byLabel), ...settings.filter((item) => !thirdParty(item)).sort(byLabel)]
  const others = settings.filter(thirdParty).sort(byLabel)
  if (others.length === 0) return [{ label: null, items: nordtal }]
  return [
    { label: "Nordtal", items: nordtal },
    { label: "Third-party", items: others },
  ].filter((group) => group.items.length > 0)
}

/** Whether the two-column layout is showing - Tailwind's `lg`. */
function useWide(): boolean {
  const query = "(min-width: 64rem)"
  const [wide, setWide] = useState(() => typeof window !== "undefined" && window.matchMedia?.(query).matches === true)
  useEffect(() => {
    const media = window.matchMedia?.(query)
    if (!media) return
    const update = () => setWide(media.matches)
    update()
    media.addEventListener("change", update)
    return () => media.removeEventListener("change", update)
  }, [])
  return wide
}

function FileRow({
  item,
  selected,
  dirty,
  onSelect,
}: {
  item: FileItem
  selected: boolean
  dirty: boolean
  onSelect: () => void
}) {
  const Icon = item.kind === "config" ? GearSixIcon : TranslateIcon
  return (
    <button
      type="button"
      onClick={onSelect}
      disabled={!item.readable}
      aria-current={selected ? "page" : undefined}
      className={cn(
        "flex h-9 w-full items-center gap-2 rounded-md px-2 text-left text-sm hover:bg-accent disabled:cursor-not-allowed disabled:text-destructive disabled:hover:bg-transparent",
        selected && "bg-accent",
      )}
    >
      <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <span className="min-w-0 flex-1 truncate">{item.label}</span>
      {dirty ? <DraftDot /> : null}
      {!item.readable ? (
        <LockIcon className="size-3.5 shrink-0" aria-label="Not readable" />
      ) : !item.writable ? (
        <LockIcon className="size-3.5 shrink-0 text-muted-foreground" aria-label="Read-only" />
      ) : null}
      <CaretRightIcon className="size-4 shrink-0 text-muted-foreground lg:hidden" aria-hidden />
    </button>
  )
}

function DraftDot() {
  return <span aria-label="Changed" className="size-1.5 shrink-0 rounded-full bg-primary" />
}

// -----------------------------------------------------------------------------------------------
// One tree, whatever its leaves are
// -----------------------------------------------------------------------------------------------

type Highlight = { id: string; seq: number; language?: Language }

/**
 * The part both kinds of file share: the search box, the one sentence about the file, the tree
 * with its open branches and counts, and the save row pinned to the bottom of the screen.
 */
function TreeView<L>({
  file,
  nodes,
  draftIds,
  matches,
  notice,
  target,
  renderLeaf,
  save,
  above,
}: {
  file: string
  nodes: TreeNode<L>[]
  /** The keys of this file that hold a draft. */
  draftIds: Set<string>
  matches: (value: L, query: string) => boolean
  notice: string | null
  target: Target | null
  renderLeaf: (leaf: TreeLeaf<L>, highlight: Highlight | null) => ReactNode
  save: ReactNode
  above?: ReactNode
}) {
  const [query, setQuery] = useState("")
  const [highlight, setHighlight] = useState<Highlight | null>(null)
  const opened = useOpened(file)
  const top = useRef<HTMLDivElement>(null)
  const search = useRef<HTMLDivElement>(null)
  const applied = useRef(-1)
  // The arrow back up only once the search box has left the screen: on a file that fits, there is
  // nowhere to go back to.
  const [scrolledAway, setScrolledAway] = useState(false)
  useEffect(() => {
    const node = search.current
    if (!node || typeof IntersectionObserver === "undefined") return
    const observer = new IntersectionObserver(([entry]) => setScrolledAway(!entry.isIntersecting))
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  const closedByDefault = useMemo(() => leafCount(nodes) > 12, [nodes])

  const shown = useMemo(
    () => (query.trim() ? filterTree(nodes, (leaf) => matches(leaf.value, query)) : nodes),
    [nodes, query, matches],
  )

  useEffect(() => {
    if (!target || target.file !== file || target.seq === applied.current) return
    const chain = ancestorsOf(nodes, target.id)
    if (!chain) return
    applied.current = target.seq
    setQuery("")
    for (const branch of chain) setOpened(file, branch, true)
    setHighlight({ id: target.id, seq: target.seq, language: target.language })
  }, [target, file, nodes])

  useEffect(() => {
    if (!highlight) return
    const timeout = window.setTimeout(() => setHighlight(null), 2400)
    return () => window.clearTimeout(timeout)
  }, [highlight])

  const isOpen = (id: string) => (query.trim() ? true : (opened[id] ?? !closedByDefault))

  return (
    <div ref={top} className="flex scroll-mt-4 flex-col gap-3">
      <InputGroup ref={search}>
        <InputGroupAddon>
          <MagnifyingGlassIcon aria-hidden />
        </InputGroupAddon>
        <InputGroupInput
          type="search"
          placeholder="Search"
          aria-label="Search this file"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </InputGroup>
      {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      {above}
      {shown.length === 0 ? (
        <p className="text-sm text-muted-foreground">{query.trim() ? "No match." : "Nothing in this file."}</p>
      ) : (
        <NodeList
          nodes={shown}
          isOpen={isOpen}
          onToggle={(id) => setOpened(file, id, !isOpen(id))}
          draftIds={draftIds}
          highlight={highlight}
          renderLeaf={renderLeaf}
        />
      )}
      <div className="pointer-events-none sticky bottom-4 mt-2 flex items-center justify-end gap-2">
        {scrolledAway ? (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="Back to the top"
            className="pointer-events-auto rounded-full bg-background/90 backdrop-blur"
            onClick={() => top.current?.scrollIntoView({ behavior: "smooth", block: "start" })}
          >
            <ArrowUpIcon aria-hidden />
          </Button>
        ) : null}
        {save}
      </div>
    </div>
  )
}

function NodeList<L>({
  nodes,
  isOpen,
  onToggle,
  draftIds,
  highlight,
  renderLeaf,
}: {
  nodes: TreeNode<L>[]
  isOpen: (id: string) => boolean
  onToggle: (id: string) => void
  draftIds: Set<string>
  highlight: Highlight | null
  renderLeaf: (leaf: TreeLeaf<L>, highlight: Highlight | null) => ReactNode
}) {
  // Neighbouring leaves share one grid; a branch interrupts it.
  const groups: Array<TreeLeaf<L>[] | TreeBranch<L>> = []
  for (const node of nodes) {
    const last = groups[groups.length - 1]
    if (node.kind === "leaf") {
      if (Array.isArray(last)) last.push(node)
      else groups.push([node])
    } else {
      groups.push(node)
    }
  }

  return (
    <div className="flex flex-col">
      {groups.map((group) => {
        if (Array.isArray(group)) {
          return (
            <div
              key={`leaves-${group[0].id}`}
              className="grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-x-6 gap-y-4 py-2"
            >
              {group.map((leaf) => (
                <div key={leaf.id} className={cn("min-w-0", leaf.wide && "col-span-full")}>
                  {renderLeaf(leaf, highlight && leaf.ids.includes(highlight.id) ? highlight : null)}
                </div>
              ))}
            </div>
          )
        }
        const open = isOpen(group.id)
        const count = idsBelow(group).filter((id) => draftIds.has(id)).length
        return (
          <Fragment key={group.id}>
            <button
              type="button"
              aria-expanded={open}
              onClick={() => onToggle(group.id)}
              className="flex h-9 w-full items-center gap-2 rounded-md text-left text-sm font-medium hover:bg-accent/50"
            >
              <CaretRightIcon
                aria-hidden
                className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")}
              />
              <span className="flex min-w-0 items-center gap-1 truncate">
                {group.labels.map((label, index) => (
                  <Fragment key={index}>
                    {index > 0 ? <CaretRightIcon aria-hidden className="size-3 shrink-0 text-muted-foreground" /> : null}
                    <span className="truncate">{label}</span>
                  </Fragment>
                ))}
              </span>
              {count > 0 ? (
                <span className="ml-auto flex shrink-0 items-center gap-1.5 pr-2 text-xs text-muted-foreground tabular-nums">
                  <DraftDot />
                  {count}
                </span>
              ) : null}
            </button>
            {open ? (
              <div className="pl-4">
                <NodeList
                  nodes={group.children}
                  isOpen={isOpen}
                  onToggle={onToggle}
                  draftIds={draftIds}
                  highlight={highlight}
                  renderLeaf={renderLeaf}
                />
              </div>
            ) : null}
          </Fragment>
        )
      })}
    </div>
  )
}

/** Scrolls a field into view and lights it up, once per jump. */
function useLanding(highlight: Highlight | null) {
  const ref = useRef<HTMLDivElement>(null)
  const seq = highlight?.seq
  useEffect(() => {
    if (seq === undefined) return
    ref.current?.scrollIntoView({ behavior: "smooth", block: "center" })
  }, [seq])
  return ref
}

const LIT = "bg-accent ring-2 ring-primary ring-offset-4 ring-offset-background"

// -----------------------------------------------------------------------------------------------
// A config file
// -----------------------------------------------------------------------------------------------

function ConfigFile({ item, target }: { item: Extract<FileItem, { kind: "config" }>; target: Target | null }) {
  const document = useConfig(item.location.path)
  return (
    <QueryState query={document} rows={8}>
      {(read) =>
        read.raw ? (
          <RawConfigView file={item.location.path} document={read} origin={item.location.origin} />
        ) : (
          <ConfigForm file={item.id} document={read as ReloadAwareConfigDocument} target={target} />
        )
      }
    </QueryState>
  )
}

type DraftValue = string | string[] | SectionValues[]

function ConfigForm({
  file,
  document,
  target,
}: {
  file: string
  document: ReloadAwareConfigDocument
  target: Target | null
}) {
  const draft = useDraft<DraftValue>(file) as Draft
  const save = useSaveConfig(document.path)
  const roles = useGuildRoles()
  const channels = useGuildChannels()
  const nodes = useMemo(() => configTree(document.entries), [document.entries])
  const byPath = useMemo(() => new Map(document.entries.map((entry) => [entry.path, entry])), [document.entries])

  const changes = useMemo(() => changed(document, draft), [document, draft])
  const count = Object.keys(changes).length
  const draftIds = useMemo(() => new Set(Object.keys(changes)), [changes])

  // `database.yml` holds what the service connects to Postgres with and is never saved from here.
  const databaseFile = document.name.split("/").pop() === "database.yml"
  const writable = document.writable && !databaseFile

  function set(path: string, value: DraftValue | undefined) {
    const entry = byPath.get(path)
    const same =
      value === undefined ||
      (entry !== undefined &&
        Object.keys(changed({ ...document, entries: [entry] } as ParsedConfigDocument, { [path]: value })).length === 0)
    setDraftValue(file, path, same ? undefined : value)
  }

  function submit() {
    const label = count === 1 ? "One setting saved." : `${count} settings saved.`
    save.mutate(
      { revision: document.revision, changes },
      {
        onSuccess: (saved) => {
          clearDraft(file)
          announceSave(label, saved.reload, document.name)
        },
      },
    )
  }

  const field = (entry: ConfigEntry, highlight: Highlight | null, layout: FieldLayout = "full") => (
    <SettingField
      key={entry.path}
      entry={entry}
      layout={layout}
      writable={writable}
      draft={draft}
      dirty={draftIds.has(entry.path)}
      roles={roles.data}
      channels={channels.data}
      highlight={highlight && highlight.id === entry.path ? highlight : null}
      onChange={(value) => set(entry.path, value)}
      onUndo={() => set(entry.path, undefined)}
    />
  )

  return (
    <TreeView<ConfigLeafValue>
      file={file}
      nodes={nodes}
      draftIds={draftIds}
      matches={configLeafMatches}
      notice={!writable ? "Read-only." : document.restartRequired ? "Applies after a restart." : null}
      target={target}
      above={save.error ? <Failure error={save.error} /> : null}
      renderLeaf={(leaf, highlight) =>
        leaf.value.kind === "entry" ? (
          field(leaf.value.entry, highlight)
        ) : leaf.value.kind === "run" ? (
          <div className="flex flex-wrap gap-4">
            {leaf.value.entries.map((entry) => (
              <div key={entry.path} className="min-w-28 flex-1">
                {field(entry, highlight, "compact")}
              </div>
            ))}
          </div>
        ) : (
          <PairedRows
            pair={leaf.value.pair}
            field={(entry, block) => field({ ...entry, label: `${block} ${entry.label}` }, highlight, "cell")}
          />
        )
      }
      save={
        count > 0 && writable ? (
          <Button type="button" className="pointer-events-auto" disabled={save.isPending} onClick={submit}>
            {save.isPending ? "Saving…" : `Save ${count}`}
          </Button>
        ) : null
      }
    />
  )
}

/**
 * `compact` is one of several side by side, where the explanation would repeat in every one of them;
 * `cell` is one half of a paired row, whose column is named once above the block - its label stays
 * for a screen reader, since a heading three rows up gives an input no name.
 */
type FieldLayout = "full" | "compact" | "cell"

function SettingField({
  entry,
  layout,
  writable,
  draft,
  dirty,
  roles,
  channels,
  highlight,
  onChange,
  onUndo,
}: {
  entry: ConfigEntry
  layout: FieldLayout
  writable: boolean
  draft: Draft
  dirty: boolean
  roles: GuildList | undefined
  channels: GuildList | undefined
  highlight: Highlight | null
  onChange: (value: DraftValue) => void
  onUndo: () => void
}) {
  const ref = useLanding(highlight)
  const explanation = layout === "full" ? explanationOf(entry) : null

  return (
    <div
      ref={ref}
      className={cn("flex min-w-0 scroll-mt-4 flex-col gap-1.5 rounded-md transition-colors duration-300", highlight && LIT)}
    >
      <div className={cn("flex flex-wrap items-center gap-x-2 gap-y-1", layout !== "cell" && "min-h-6")}>
        <Label htmlFor={entry.path} className={layout === "cell" ? "sr-only" : "min-w-0 text-sm font-normal"}>
          {entry.label}
        </Label>
        {!entry.inSchema ? <NotInSchemaBadge /> : null}
        {entry.environmentOverridden ? <EnvironmentOverriddenBadge /> : null}
        {dirty ? (
          <>
            <DraftDot />
            <Button type="button" variant="ghost" size="icon-xs" aria-label={`Undo ${entry.label}`} onClick={onUndo}>
              <ArrowCounterClockwiseIcon aria-hidden />
            </Button>
          </>
        ) : null}
      </div>
      <Control
        entry={entry}
        draft={draft}
        disabled={!writable || !entry.editable}
        roles={roles}
        channels={channels}
        onChange={onChange}
      />
      {explanation ? <p className="whitespace-pre-wrap text-xs text-muted-foreground">{explanation}</p> : null}
    </div>
  )
}

/** Two sections with the same keys, as one row per key: the key's name, then both fields. */
function PairedRows({
  pair,
  field,
}: {
  pair: PairedBlocks
  field: (entry: ConfigEntry, block: string) => ReactNode
}) {
  // A number needs room for four digits and no more; the other half - a hex colour, a name - gets
  // the rest. A custom property rather than an inline grid template, so `fits-on-a-phone.test.ts`
  // still sees a column count in the class.
  const span = (entry: ConfigEntry) =>
    entry.type === "INTEGER" || entry.type === "DECIMAL" ? "5rem" : "minmax(0,1fr)"
  const columns = {
    "--paired-columns": `auto ${span(pair.rows[0].left)} ${span(pair.rows[0].right)}`,
  } as CSSProperties

  return (
    <ul className="flex max-w-xl flex-col">
      <li
        className="grid grid-cols-[var(--paired-columns)] items-end gap-x-3 pb-1 text-sm"
        style={columns}
      >
        <span />
        <span>{pair.left.label}</span>
        <span>{pair.right.label}</span>
      </li>
      {pair.rows.map((row) => (
        <li
          key={row.key}
          className="grid grid-cols-[var(--paired-columns)] items-center gap-x-3 py-1.5"
          style={columns}
        >
          <span className="text-sm text-muted-foreground">{row.left.label}</span>
          {field(row.left, pair.left.label)}
          {field(row.right, pair.right.label)}
        </li>
      ))}
    </ul>
  )
}

// -----------------------------------------------------------------------------------------------
// A message bundle
// -----------------------------------------------------------------------------------------------

/** One key's draft: per language, a new text, `null` for "back to the jar", absent for no change. */
type MessageDraft = Partial<Record<Language, string | null>>

function BundleFile({ item, target }: { item: Extract<FileItem, { kind: "bundle" }>; target: Target | null }) {
  const document = useMessageBundle(item.location.path)
  return (
    <QueryState query={document} rows={8}>
      {(read) => <BundleForm file={item.id} bundle={read} target={target} />}
    </QueryState>
  )
}

function BundleForm({ file, bundle, target }: { file: string; bundle: MessageBundle; target: Target | null }) {
  const draft = useDraft<MessageDraft>(file)
  const save = useSaveMessageBundle(bundle.path)
  const [warnings, setWarnings] = useState<string[]>([])
  const nodes = useMemo(() => messageTree(bundle.entries), [bundle.entries])
  const byKey = useMemo(() => new Map(bundle.entries.map((entry) => [entry.key, entry])), [bundle.entries])

  const draftIds = useMemo(() => new Set(Object.keys(draft)), [draft])
  const count = Object.values(draft).reduce((sum, languages) => sum + Object.keys(languages).length, 0)
  const blocked = Object.entries(draft).some(([key, languages]) => {
    const entry = byKey.get(key)
    return entry !== undefined && Object.values(languages).some((text) => unknownPlaceholders(entry, text).length > 0)
  })

  function set(key: string, language: Language, value: string | null | undefined) {
    const entry = byKey.get(key)
    if (!entry) return
    const saved = overrideOf(entry, language)
    let next = value
    if (typeof next === "string" && next === (saved ?? packagedOf(entry, language) ?? "")) next = undefined
    if (next === null && saved === undefined) next = undefined
    const languages: MessageDraft = { ...(draft[key] ?? {}) }
    if (next === undefined) delete languages[language]
    else languages[language] = next
    setDraftValue(file, key, Object.keys(languages).length > 0 ? languages : undefined)
  }

  function submit() {
    const label = count === 1 ? "One text saved." : `${count} texts saved.`
    save.mutate(
      { changes: draft },
      {
        onSuccess: (saved) => {
          clearDraft(file)
          const unknown = saved.reload?.unknown ?? []
          setWarnings([...unknown.map((key) => `${key} is in the override file and in no bundle`), ...saved.warnings])
          announceSave(label, saved.reload, bundle.path)
        },
      },
    )
  }

  return (
    <TreeView<MessageEntry>
      file={file}
      nodes={nodes}
      draftIds={draftIds}
      matches={messageLeafMatches}
      notice={bundle.writable ? null : "Read-only."}
      target={target}
      above={
        <>
          {save.error ? <Failure error={save.error} /> : null}
          {warnings.length > 0 ? (
            <ul className="flex flex-col gap-1 text-xs text-warning">
              {warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          ) : null}
        </>
      }
      renderLeaf={(leaf, highlight) => (
        <MessageField
          entry={leaf.value}
          writable={bundle.writable}
          draft={draft[leaf.value.key]}
          highlight={highlight}
          onChange={(language, value) => set(leaf.value.key, language, value)}
        />
      )}
      save={
        count > 0 && bundle.writable ? (
          <Button type="button" className="pointer-events-auto" disabled={save.isPending || blocked} onClick={submit}>
            {save.isPending ? "Saving…" : `Save ${count}`}
          </Button>
        ) : null
      }
    />
  )
}

function MessageField({
  entry,
  writable,
  draft,
  highlight,
  onChange,
}: {
  entry: MessageEntry
  writable: boolean
  draft: MessageDraft | undefined
  highlight: Highlight | null
  /** `undefined` drops this language's draft, `null` asks for the packaged text back. */
  onChange: (language: Language, value: string | null | undefined) => void
}) {
  const [language, setLanguage] = useState<Language>("en")
  const ref = useLanding(highlight)
  const input = useRef<HTMLTextAreaElement>(null)
  const id = `message-${entry.key}`

  useEffect(() => {
    if (highlight?.language) setLanguage(highlight.language)
  }, [highlight?.seq, highlight?.language])

  const typed = draft?.[language]
  const packaged = packagedOf(entry, language)
  const override = overrideOf(entry, language)
  const value = typed === undefined ? (override ?? packaged ?? "") : typed === null ? (packaged ?? "") : typed
  const unknown = typeof typed === "string" ? unknownPlaceholders(entry, typed) : []

  const overridden = (tab: Language) => {
    const own = draft?.[tab]
    return own === null ? false : own !== undefined ? true : overrideOf(entry, tab) !== undefined
  }
  const empty = (tab: Language) => !packagedOf(entry, tab) && !overridden(tab)

  function insert(token: string) {
    const element = input.current
    const start = element?.selectionStart ?? value.length
    const end = element?.selectionEnd ?? value.length
    onChange(language, value.slice(0, start) + token + value.slice(end))
    requestAnimationFrame(() => {
      element?.focus()
      element?.setSelectionRange(start + token.length, start + token.length)
    })
  }

  return (
    <div
      ref={ref}
      className={cn("flex min-w-0 scroll-mt-4 flex-col gap-1.5 rounded-md transition-colors duration-300", highlight && LIT)}
    >
      <div className="flex min-h-6 items-center gap-2">
        <Label htmlFor={id} className="min-w-0 text-sm font-normal">
          {messageName(entry)}
        </Label>
        {!entry.inBundle ? (
          <Badge variant="outline" className="shrink-0 text-muted-foreground">
            not in bundle
          </Badge>
        ) : null}
        {draft !== undefined ? <DraftDot /> : null}
      </div>
      <InputGroup className={cn(unknown.length > 0 && "border-destructive")}>
        <InputGroupTextarea
          ref={input}
          id={id}
          rows={1}
          value={value}
          disabled={!writable}
          spellCheck={false}
          aria-invalid={unknown.length > 0}
          onChange={(event) => onChange(language, event.target.value)}
          className="field-sizing-content min-h-8 py-1.5 text-sm"
        />
        <InputGroupAddon align="inline-end" className="self-start py-1">
          {typed !== undefined ? (
            <InputGroupButton size="icon-xs" aria-label="Undo" onClick={() => onChange(language, undefined)}>
              <ArrowCounterClockwiseIcon aria-hidden />
            </InputGroupButton>
          ) : null}
          {override !== undefined && typed !== null && writable ? (
            <InputGroupButton size="icon-xs" aria-label="Reset to the packaged text" onClick={() => onChange(language, null)}>
              <EraserIcon aria-hidden />
            </InputGroupButton>
          ) : null}
          <Tabs value={language} onValueChange={(next) => setLanguage(next as Language)}>
            <TabsList className="group-data-horizontal/tabs:h-6 p-0.5">
              {(["en", "de"] as const).map((tab) => (
                <TabsTrigger
                  key={tab}
                  value={tab}
                  className={cn("gap-1 px-1.5 text-xs", empty(tab) && "opacity-50")}
                >
                  {tab.toUpperCase()}
                  {overridden(tab) ? <span aria-label="Overridden" className="size-1 rounded-full bg-primary" /> : null}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        </InputGroupAddon>
      </InputGroup>
      {unknown.length > 0 ? (
        <p className="text-xs text-destructive">Unknown placeholder {unknown.join(" ")}</p>
      ) : null}
      {entry.args.length > 0 ? (
        <div className="flex flex-wrap gap-1">
          {entry.args.map((arg) => (
            <button
              key={arg.name}
              type="button"
              disabled={!writable}
              onClick={() => insert(tokenOf(arg))}
              className="rounded-full disabled:pointer-events-none"
            >
              <Badge variant="outline" className="font-mono">
                {tokenOf(arg)}
              </Badge>
            </button>
          ))}
        </div>
      ) : null}
      {entry.description ? <p className="text-xs text-muted-foreground">{entry.description}</p> : null}
    </div>
  )
}
