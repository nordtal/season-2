import { ArrowCounterClockwiseIcon, CaretRightIcon, EraserIcon } from "@phosphor-icons/react"
import { useEffect, useMemo, useRef, useState } from "react"
import type { ReactNode } from "react"
import { cn } from "cn"

import type { MessageBundle, MessageEntry } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { overrideOf, packagedOf, tokenOf, unknownPlaceholders, type Language } from "@/lib/message-text"
import { useMessageBundle, useSaveMessageBundle } from "@/lib/queries"
import { messageLeafMatches, messageName, messageTree } from "@/lib/settings-tree"
import { Failure, QueryState } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupTextarea } from "@/components/ui/input-group"
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
import {
  DraftDot,
  type FileItem,
  type Highlight,
  LIT,
  type Target,
  TreeView,
  UNSEEN,
  isLanguage,
  useLanding,
  useWide,
} from "@/components/steward/settings-view"

/** A message bundle of the Settings tab: every key with its translations, and the save. */

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

export function BundleFile({ item, target }: { item: Extract<FileItem, { kind: "bundle" }>; target: Target | null }) {
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
          setWarnings(saved.warnings)
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
