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
import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"
import { cn } from "cn"
import type { CSSVars } from "@/lib/utils"

import type {
  ConfigDocument,
  ConfigEntry,
  ConfigLocation,
  GuildList,
  MessageBundle,
  MessageBundleLocation,
  MessageEntry,
  ReloadAwareConfigDocument,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, setOpened, useDirtyFiles, useDraft, useOpened } from "@/lib/drafts"
import { overrideOf, packagedOf, tokenOf, unknownPlaceholders, type Language } from "@/lib/message-text"
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
import { onPendingJump, onPendingMessageJump, takePendingJump, takePendingMessageJump } from "@/lib/settings-search"
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
import { changed, Control, EnvironmentOverriddenBadge, type Draft } from "@/components/steward/configuration"
import { explanationOf } from "@/components/steward/config-controls"
import { fileTitle, translationsTitle } from "@/lib/words"
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
import {
  ResponsiveDialog,
  ResponsiveDialogClose,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"
import { MessagePreview } from "@/components/steward/message-preview"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"

/** The service the network's own settings are published under, the ones every process reads. */
export const NETWORK = "network"

/**
 * The Settings & Translations tab: the service's config files and bundles in one list, the chosen one as a tree.
 *
 * The open file is the page's `?file=`, handed in and reported back rather than kept here.
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
  useLayoutEffect(() => {
    report.current = onFile
  })

  const files = useMemo(
    () => filesOf(service, configs.data ?? [], bundles.data ?? []),
    [service, configs.data, bundles.data],
  )
  const loading = configs.isPending || bundles.isPending
  /** On a wide screen the first readable file is shown when none is chosen, derived rather than written to the URL. */
  const shown = file ?? (wide ? files.find((item) => item.readable)?.id : undefined)
  const selected = files.find((item) => item.id === shown)

  /** A jump from the command palette; each gets a new number, so the same hit twice lands twice. */
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
      <nav
        aria-label="Files"
        className={cn("flex flex-col gap-0.5 lg:sticky lg:top-4", file !== undefined && "max-lg:hidden")}
      >
        {failure ? <Failure error={failure} /> : null}
        {loading ? (
          [0, 1, 2].map((index) => (
            <div key={index} className="flex h-9 items-center gap-2 px-2">
              <SkeletonText className="text-sm" width="medium" />
            </div>
          ))
        ) : files.length === 0 && !failure ? (
          <p className="px-2 text-sm text-muted-foreground">No files.</p>
        ) : (
          files.map((item) => (
            <FileRow
              key={item.id}
              item={item}
              selected={item.id === shown}
              dirty={dirty.includes(item.id)}
              onSelect={() => onFile(item.id)}
            />
          ))
        )}
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
                  if (historyEntryIndex() > 0) window.history.back()
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

/** The router's own position in the browser history, or 0 for an entry it never numbered. */
function historyEntryIndex(): number {
  const state: unknown = window.history.state
  const key = "__TSR_index"
  if (typeof state !== "object" || state === null || !(key in state)) return 0
  const index = state[key]
  return typeof index === "number" ? index : 0
}

/** The translations first, then the groups of settings. */
function filesOf(service: string, configs: ConfigLocation[], bundles: MessageBundleLocation[]): FileItem[] {
  const byLabel = (a: FileItem, b: FileItem) => a.label.localeCompare(b.label)
  const settings: FileItem[] = configs
    .filter((file) => file.service === service)
    .map((location) => ({
      kind: "config",
      id: location.path,
      label: fileTitle(location.name),
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
  return [...translations.toSorted(byLabel), ...settings.toSorted(byLabel)]
}

/** Whether the two-column layout, Tailwind's `lg`, is showing. */
function useWide(): boolean {
  const query = "(min-width: 64rem)"
  const [wide, setWide] = useState(() => typeof window !== "undefined" && window.matchMedia?.(query).matches)
  useEffect(() => {
    const media = window.matchMedia?.(query)
    if (!media) return undefined
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

type Highlight = { id: string; seq: number; language?: Language }

/** The part both kinds of file share: search box, description, tree with counts, and the pinned save row. */
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
  collapsed,
  list,
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
  /** Every branch starts closed, whatever the file's size. */
  collapsed?: boolean
  /** Leaves are full-width rows under each other rather than a grid of fields. */
  list?: boolean
}) {
  const [query, setQuery] = useState("")
  const [highlight, setHighlight] = useState<Highlight | null>(null)
  const opened = useOpened(file)
  const top = useRef<HTMLDivElement>(null)
  const search = useRef<HTMLDivElement>(null)
  const [appliedSeq, setAppliedSeq] = useState(-1)
  /** The arrow back up appears only once the search box has left the screen. */
  const [scrolledAway, setScrolledAway] = useState(false)
  useEffect(() => {
    const node = search.current
    if (!node || typeof IntersectionObserver === "undefined") return undefined
    const observer = new IntersectionObserver(([entry]) => setScrolledAway(!entry.isIntersecting))
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  const closedByDefault = useMemo(() => collapsed === true || leafCount(nodes) > 12, [nodes, collapsed])

  const shown = useMemo(
    () => (query.trim() ? filterTree(nodes, (leaf) => matches(leaf.value, query)) : nodes),
    [nodes, query, matches],
  )

  /** Opens the chain to a new jump target during render, so it lands in one commit; `appliedSeq` marks the last. */
  const chainForTarget =
    target && target.file === file && target.seq !== appliedSeq ? ancestorsOf(nodes, target.id) : null

  if (chainForTarget && target) {
    setAppliedSeq(target.seq)
    setQuery("")
    setHighlight({ id: target.id, seq: target.seq, language: target.language })
  }

  /** Opening a branch writes to the shared draft store, an external system, so this part stays an effect. */
  useEffect(() => {
    if (!chainForTarget) return
    for (const branch of chainForTarget) setOpened(file, branch, true)
  }, [chainForTarget, file])

  useEffect(() => {
    if (!highlight) return undefined
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
          list={list}
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
  list,
}: {
  nodes: TreeNode<L>[]
  isOpen: (id: string) => boolean
  onToggle: (id: string) => void
  draftIds: Set<string>
  highlight: Highlight | null
  renderLeaf: (leaf: TreeLeaf<L>, highlight: Highlight | null) => ReactNode
  list?: boolean
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
              className={cn(
                list ? "flex flex-col" : "grid grid-cols-[repeat(auto-fill,minmax(13rem,1fr))] gap-x-6 gap-y-4 py-2",
              )}
            >
              {group.map((leaf) => (
                <div key={leaf.id} className={cn("min-w-0", !list && leaf.wide && "col-span-full")}>
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
                    {index > 0 ? (
                      <CaretRightIcon aria-hidden className="size-3 shrink-0 text-muted-foreground" />
                    ) : null}
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
                  list={list}
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

/** A sequence number no highlight reaches, so the first render always counts as new. */
const UNSEEN = Symbol("unseen")

function isLanguage(value: string): value is Language {
  return value === "en" || value === "de"
}

/** Whether `document` carries the two reload fields every GET and PUT of a group sends. */
function isReloadAware(document: ConfigDocument): document is ReloadAwareConfigDocument {
  return "restartRequired" in document && typeof document.restartRequired === "boolean"
}

function ConfigFile({ item, target }: { item: Extract<FileItem, { kind: "config" }>; target: Target | null }) {
  const document = useConfig(item.location.path)
  return (
    <QueryState query={document} rows={8}>
      {(read) => {
        if (!isReloadAware(read)) {
          throw new Error(`${item.location.path}: steward answered a config document with no restartRequired`)
        }
        return <ConfigForm file={item.id} document={read} target={target} />
      }}
    </QueryState>
  )
}

type DraftValue = string | string[] | SectionValues[]

function isSectionValues(value: unknown): value is SectionValues {
  return (
    typeof value === "object" &&
    value !== null &&
    Object.values(value).every(
      (entry) =>
        typeof entry === "string" ||
        (Array.isArray(entry) && entry.every((item) => typeof item === "string" || isSectionValues(item))),
    )
  )
}

function isDraftValue(value: unknown): value is DraftValue {
  return (
    typeof value === "string" ||
    (Array.isArray(value) && value.every((item) => typeof item === "string")) ||
    (Array.isArray(value) && value.every(isSectionValues))
  )
}

function isDraftValueRecord(value: unknown): value is Record<string, DraftValue> {
  return typeof value === "object" && value !== null && Object.values(value).every(isDraftValue)
}

function ConfigForm({
  file,
  document,
  target,
}: {
  file: string
  document: ReloadAwareConfigDocument
  target: Target | null
}) {
  const draft = useDraft<DraftValue>(file, isDraftValueRecord) as Draft
  const save = useSaveConfig(document.path)
  const roles = useGuildRoles()
  const channels = useGuildChannels()
  const nodes = useMemo(() => configTree(document.entries), [document.entries])
  const byPath = useMemo(() => new Map(document.entries.map((entry) => [entry.path, entry])), [document.entries])

  const changes = useMemo(() => changed(document, draft), [document, draft])
  const count = Object.keys(changes).length
  const draftIds = useMemo(() => new Set(Object.keys(changes)), [changes])

  const writable = document.writable

  function set(path: string, value: DraftValue | undefined) {
    const entry = byPath.get(path)
    const same =
      value === undefined ||
      (entry !== undefined && Object.keys(changed({ ...document, entries: [entry] }, { [path]: value })).length === 0)
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
      notice={
        document.problem
          ? `Refused: ${document.problem}`
          : !writable
            ? "Read-only."
            : document.restartRequired
              ? "Applies after a restart."
              : null
      }
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

/** `compact` sits beside others and drops the explanation; `cell` is half a paired row, named for screen readers. */
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
      className={cn(
        "flex min-w-0 scroll-mt-4 flex-col gap-1.5 rounded-md transition-colors duration-300",
        highlight && LIT,
      )}
    >
      <div className={cn("flex flex-wrap items-center gap-x-2 gap-y-1", layout !== "cell" && "min-h-6")}>
        <Label htmlFor={entry.path} className={layout === "cell" ? "sr-only" : "min-w-0 text-sm font-normal"}>
          {entry.label}
        </Label>
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

/** A number gets room for four digits and the other half the rest, via a custom property the phone test reads. */
function span(entry: ConfigEntry): string {
  return entry.type === "INTEGER" || entry.type === "DECIMAL" ? "5rem" : "minmax(0,1fr)"
}

/** Two sections with the same keys, as one row per key: the key's name, then both fields. */
function PairedRows({ pair, field }: { pair: PairedBlocks; field: (entry: ConfigEntry, block: string) => ReactNode }) {
  const columns: CSSVars = {
    "--paired-columns": `auto ${span(pair.rows[0].left)} ${span(pair.rows[0].right)}`,
  }

  return (
    <ul className="flex max-w-xl flex-col">
      <li className="grid grid-cols-[var(--paired-columns)] items-end gap-x-3 pb-1 text-sm" style={columns}>
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

/** One key's draft: per language, a new text, `null` for "back to the jar", absent for no change. */
type MessageDraft = Partial<Record<Language, string | null>>

function isMessageDraft(value: unknown): value is MessageDraft {
  if (typeof value !== "object" || value === null) return false
  return Object.entries(value).every(
    ([key, entry]) => (key === "en" || key === "de") && (entry === null || typeof entry === "string"),
  )
}

function isMessageDraftRecord(value: unknown): value is Record<string, MessageDraft> {
  return typeof value === "object" && value !== null && Object.values(value).every(isMessageDraft)
}

function BundleFile({ item, target }: { item: Extract<FileItem, { kind: "bundle" }>; target: Target | null }) {
  const document = useMessageBundle(item.location.path)
  return (
    <QueryState query={document} rows={8}>
      {(read) => <BundleForm file={item.id} bundle={read} target={target} />}
    </QueryState>
  )
}

function BundleForm({ file, bundle, target }: { file: string; bundle: MessageBundle; target: Target | null }) {
  const draft = useDraft<MessageDraft>(file, isMessageDraftRecord)
  const save = useSaveMessageBundle(bundle.path)
  const [warnings, setWarnings] = useState<string[]>([])
  const nodes = useMemo(() => messageTree(bundle.entries), [bundle.entries])
  const byKey = useMemo(() => new Map(bundle.entries.map((entry) => [entry.key, entry])), [bundle.entries])
  // One key open at a time: opening another closes this one, and its draft stays where it is.
  const [openKey, setOpenKey] = useState<string | null>(null)

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
    const languages: MessageDraft = { ...draft[key] }
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

  const saveButton = (className?: string) =>
    count > 0 && bundle.writable ? (
      <Button type="button" className={className} disabled={save.isPending || blocked} onClick={submit}>
        {save.isPending ? "Saving…" : `Save ${count}`}
      </Button>
    ) : null

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
      collapsed
      list
      renderLeaf={(leaf, highlight) => (
        <MessageRow
          entry={leaf.value}
          open={openKey === leaf.value.key}
          onOpen={(open) => setOpenKey(open ? leaf.value.key : null)}
          writable={bundle.writable}
          draft={draft[leaf.value.key]}
          highlight={highlight}
          onChange={(language, value) => set(leaf.value.key, language, value)}
          save={saveButton()}
        />
      )}
      save={saveButton("pointer-events-auto")}
    />
  )
}

/** One key: its name and text on one line, and once opened its field, inline when wide and in a sheet below. */
function MessageRow({
  entry,
  open,
  onOpen,
  writable,
  draft,
  highlight,
  onChange,
  save,
}: Parameters<typeof MessageField>[0] & {
  open: boolean
  onOpen: (open: boolean) => void
  /** The file's save button, repeated in the sheet: the page's own is behind it. */
  save: ReactNode
}) {
  const wide = useWide()
  const name = messageName(entry)
  const english =
    draft?.en === undefined
      ? (overrideOf(entry, "en") ?? packagedOf(entry, "en"))
      : draft.en === null
        ? packagedOf(entry, "en")
        : draft.en
  const text = english || (draft?.de ?? overrideOf(entry, "de") ?? packagedOf(entry, "de") ?? "")

  // A jump from the command palette lands on a key by opening it.
  useEffect(() => {
    if (highlight) onOpen(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlight?.seq])

  const field = (
    <MessageField entry={entry} writable={writable} draft={draft} highlight={highlight} onChange={onChange} bare />
  )
  return (
    <div className="min-w-0">
      <button
        type="button"
        aria-label={name}
        aria-expanded={open}
        onClick={() => onOpen(!open)}
        className={cn(
          "flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-accent/50",
          open && wide && "bg-accent/50",
        )}
      >
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-sm">{name}</span>
          <MessagePreview text={text} args={entry.args} className="text-xs text-muted-foreground" />
        </span>
        {!entry.inBundle ? (
          <Badge variant="outline" className="shrink-0 text-muted-foreground">
            not in bundle
          </Badge>
        ) : null}
        {draft !== undefined ? <DraftDot /> : null}
        <CaretRightIcon
          aria-hidden
          className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && wide && "rotate-90")}
        />
      </button>
      {wide ? (
        open ? (
          <div className="px-2 pt-1 pb-3">{field}</div>
        ) : null
      ) : (
        <ResponsiveDialog open={open} onOpenChange={onOpen}>
          <ResponsiveDialogContent>
            <ResponsiveDialogHeader>
              <ResponsiveDialogTitle>{name}</ResponsiveDialogTitle>
            </ResponsiveDialogHeader>
            {field}
            <ResponsiveDialogFooter>
              {save}
              <ResponsiveDialogClose asChild>
                <Button type="button" variant="outline">
                  Done
                </Button>
              </ResponsiveDialogClose>
            </ResponsiveDialogFooter>
          </ResponsiveDialogContent>
        </ResponsiveDialog>
      )}
    </div>
  )
}

function MessageField({
  entry,
  writable,
  draft,
  highlight,
  onChange,
  bare,
}: {
  entry: MessageEntry
  writable: boolean
  draft: MessageDraft | undefined
  highlight: Highlight | null
  /** `undefined` drops this language's draft, `null` asks for the packaged text back. */
  onChange: (language: Language, value: string | null | undefined) => void
  /** Without its own name above it: the row or the sheet it sits in already says it. */
  bare?: boolean
}) {
  const [language, setLanguage] = useState<Language>("en")
  const ref = useLanding(highlight)
  const input = useRef<HTMLTextAreaElement>(null)
  const id = `message-${entry.key}`

  const seenSeq = useRef<number | undefined | typeof UNSEEN>(UNSEEN)
  if (seenSeq.current !== highlight?.seq) {
    seenSeq.current = highlight?.seq
    if (highlight?.language) setLanguage(highlight.language)
  }

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
      className={cn(
        "flex min-w-0 scroll-mt-4 flex-col gap-1.5 rounded-md transition-colors duration-300",
        highlight && LIT,
      )}
    >
      {bare ? null : (
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
      )}
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
            <InputGroupButton
              size="icon-xs"
              aria-label="Reset to the packaged text"
              onClick={() => onChange(language, null)}
            >
              <EraserIcon aria-hidden />
            </InputGroupButton>
          ) : null}
          <Tabs value={language} onValueChange={(next) => (isLanguage(next) ? setLanguage(next) : undefined)}>
            <TabsList className="group-data-horizontal/tabs:h-6 p-0.5">
              {(["en", "de"] as const).map((tab) => (
                <TabsTrigger key={tab} value={tab} className={cn("gap-1 px-1.5 text-xs", empty(tab) && "opacity-50")}>
                  {tab.toUpperCase()}
                  {overridden(tab) ? <span aria-label="Overridden" className="size-1 rounded-full bg-primary" /> : null}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        </InputGroupAddon>
      </InputGroup>
      {unknown.length > 0 ? <p className="text-xs text-destructive">Unknown placeholder {unknown.join(" ")}</p> : null}
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
              <Badge variant={arg.global ? "secondary" : "outline"} className="font-mono">
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
