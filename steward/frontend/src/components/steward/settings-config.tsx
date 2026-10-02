import { ArrowCounterClockwiseIcon } from "@phosphor-icons/react"
import { useMemo } from "react"
import type { ReactNode } from "react"
import { cn } from "cn"

import type { CSSVars } from "@/lib/utils"
import type { ConfigDocument, ConfigEntry } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { useConfig, useSaveConfig } from "@/lib/queries"
import { configLeafMatches, configTree, type ConfigLeafValue } from "@/lib/settings-tree"
import { changed, Control, EnvironmentOverriddenBadge, type Draft } from "@/components/steward/configuration"
import { explanationOf } from "@/components/steward/config-controls"
import { customEditor } from "@/components/steward/config-editors"
import type { PairedBlocks } from "@/components/steward/paired-blocks"
import { Failure, QueryState } from "@/components/steward/query-state"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  DraftDot,
  type FileItem,
  type Highlight,
  LIT,
  type Target,
  TreeView,
  useLanding,
} from "@/components/steward/settings-view"

/** A config file of the Settings tab: its form, its fields and the save that reloads the service. */

/** Whether `document` carries the two reload fields every GET and PUT of a group sends. */
function isReloadAware(document: ConfigDocument): document is ConfigDocument {
  return "restartRequired" in document && typeof document.restartRequired === "boolean"
}

export function ConfigFile({ item, target }: { item: Extract<FileItem, { kind: "config" }>; target: Target | null }) {
  const document = useConfig(item.location.path)
  const Editor = customEditor(item.editor) ?? ConfigForm
  return (
    <QueryState query={document} rows={8}>
      {(read) => {
        if (!isReloadAware(read)) {
          throw new Error(`${item.location.path}: steward answered a config document with no restartRequired`)
        }
        return <Editor file={item.id} document={read} target={target} />
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

function ConfigForm({ file, document, target }: { file: string; document: ConfigDocument; target: Target | null }) {
  const draft = useDraft<DraftValue>(file, isDraftValueRecord) as Draft
  const save = useSaveConfig(document.path)
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
  highlight,
  onChange,
  onUndo,
}: {
  entry: ConfigEntry
  layout: FieldLayout
  writable: boolean
  draft: Draft
  dirty: boolean
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
      <Control entry={entry} draft={draft} disabled={!writable || !entry.editable} onChange={onChange} />
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
