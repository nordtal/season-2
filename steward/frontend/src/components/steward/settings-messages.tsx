import { CaretRightIcon, WarningIcon } from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { cn } from "cn"

import type { MessageBundle, MessageEntry, MessageFallback, Warning } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { ENGLISH, isLanguage, languagesOf, overrideOf, packagedOf, shownOf, type Language } from "@/lib/message-text"
import { useMessageBundle, useMessageFallbacks, useSaveMessageBundle } from "@/lib/queries"
import { messageLeafMatches, messageName, messageTree } from "@/lib/settings-tree"
import { Failure, QueryState } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { MessagePreview } from "@/components/steward/message-preview"
import {
  MessageEditor,
  type MessageDraft,
  type MessageEditorProps,
} from "@/components/steward/message-editor/message-editor"
import { DraftDot, type FileItem, type Target, TreeView } from "@/components/steward/settings-view"
import { message, t } from "@/lib/texts"

/** A message bundle of the Settings tab: every key with its translations, and the save. */

function isMessageDraft(value: unknown): value is MessageDraft {
  if (typeof value !== "object" || value === null) return false
  return Object.entries(value).every(
    ([key, entry]) =>
      isLanguage(key) &&
      (entry === null || (Array.isArray(entry) && entry.every((variant) => typeof variant === "string"))),
  )
}

function same(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((text, index) => text === b[index])
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
  const takeOver = useSaveMessageBundle(bundle.path)
  const fallbacks = useMessageFallbacks()
  const [warnings, setWarnings] = useState<Warning[]>([])
  const nodes = useMemo(() => messageTree(bundle.entries), [bundle.entries])
  const byKey = useMemo(() => new Map(bundle.entries.map((entry) => [entry.key, entry])), [bundle.entries])
  const languages = useMemo(() => languagesOf(bundle.entries), [bundle.entries])
  const fallenBack = useMemo(() => {
    const found = new Map<string, MessageFallback[]>()
    for (const fallback of fallbacks.data ?? []) {
      if (fallback.path !== bundle.path) continue
      found.set(fallback.key, [...(found.get(fallback.key) ?? []), fallback])
    }
    return found
  }, [fallbacks.data, bundle.path])
  // One key open at a time: opening another closes this one, and its draft stays where it is.
  const [openKey, setOpenKey] = useState<string | null>(null)

  const draftIds = useMemo(() => new Set(Object.keys(draft)), [draft])
  const count = Object.values(draft).reduce((sum, changed) => sum + Object.keys(changed).length, 0)

  function set(key: string, language: Language, value: string[] | null | undefined) {
    const entry = byKey.get(key)
    if (!entry) return
    const saved = overrideOf(entry, language)
    let next = value
    if (Array.isArray(next) && same(next, shownOf(entry, language))) next = undefined
    if (next === null && saved === undefined) next = undefined
    const changed: MessageDraft = { ...draft[key] }
    if (next === undefined) delete changed[language]
    else changed[language] = next
    setDraftValue(file, key, Object.keys(changed).length > 0 ? changed : undefined)
  }

  function submit() {
    const label = t("steward.settings.texts-saved", { count })
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

  /** Saves a fallen-back override as it stands, which writes it over the jar's text of now; its draft goes. */
  function takeOverOf(key: string) {
    return (language: Language, texts: string[]) =>
      takeOver.mutate(
        { changes: { [key]: { [language]: texts } } },
        {
          onSuccess: (saved) => {
            set(key, language, undefined)
            setWarnings(saved.warnings)
            announceSave(t("steward.settings.texts-saved", { count: 1 }), saved.reload, bundle.path)
          },
        },
      )
  }

  const saveButton = (className?: string) =>
    count > 0 && bundle.writable ? (
      <Button type="button" className={className} disabled={save.isPending} onClick={submit}>
        {save.isPending ? t("steward.form.saving") : t("steward.form.save-count", { count })}
      </Button>
    ) : null

  const failure = save.error ?? takeOver.error
  return (
    <TreeView<MessageEntry>
      file={file}
      nodes={nodes}
      draftIds={draftIds}
      matches={messageLeafMatches}
      notice={bundle.writable ? null : t("steward.settings.read-only")}
      target={target}
      above={
        <>
          {failure ? <Failure error={failure} /> : null}
          {warnings.length > 0 ? (
            <ul className="flex flex-col gap-1 text-xs text-warning">
              {warnings.map((warning, index) => (
                <li key={`${warning.key}/${warning.language}/${index}`}>
                  <span className="font-mono">{warning.key}</span> {message(warning.text)}
                </li>
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
          bundle={bundle.path}
          languages={languages}
          open={openKey === leaf.value.key}
          onOpen={(open) => setOpenKey(open ? leaf.value.key : null)}
          writable={bundle.writable}
          draft={draft[leaf.value.key]}
          highlight={highlight}
          onChange={(language, value) => set(leaf.value.key, language, value)}
          fallbacks={fallenBack.get(leaf.value.key) ?? []}
          onTakeOver={takeOverOf(leaf.value.key)}
          takingOver={takeOver.isPending}
        />
      )}
      save={saveButton("pointer-events-auto")}
    />
  )
}

/** One key: its name and text on one line, and once opened its editor right below it, on a phone as well. */
function MessageRow({
  open,
  onOpen,
  ...editor
}: MessageEditorProps & {
  open: boolean
  onOpen: (open: boolean) => void
}) {
  const { entry, draft, highlight, fallbacks } = editor
  const name = messageName(entry)
  const typed = draft?.[ENGLISH]
  const text = (typed === undefined ? shownOf(entry, ENGLISH) : (typed ?? packagedOf(entry, ENGLISH)))[0] ?? ""

  // A jump from the command palette lands on a key by opening it.
  useEffect(() => {
    if (highlight) onOpen(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlight?.seq])

  return (
    <div className="min-w-0">
      <button
        type="button"
        aria-label={name}
        aria-expanded={open}
        onClick={() => onOpen(!open)}
        className={cn(
          "flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-accent/50",
          open && "bg-accent/50",
        )}
      >
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="truncate text-sm">{name}</span>
          <MessagePreview text={text} format={entry.format} className="text-xs text-muted-foreground" />
        </span>
        {!entry.inBundle ? (
          <Badge variant="outline" className="shrink-0 text-muted-foreground">
            {t("steward.settings.not-in-bundle")}
          </Badge>
        ) : null}
        {fallbacks.length > 0 ? (
          <WarningIcon aria-label={t("steward.message-editor.fallen-back")} className="size-4 shrink-0 text-warning" />
        ) : null}
        {draft !== undefined ? <DraftDot /> : null}
        <CaretRightIcon
          aria-hidden
          className={cn("size-4 shrink-0 text-muted-foreground transition-transform", open && "rotate-90")}
        />
      </button>
      {open ? (
        <div className="px-2 pt-1 pb-3">
          <MessageEditor {...editor} />
        </div>
      ) : null}
    </div>
  )
}
