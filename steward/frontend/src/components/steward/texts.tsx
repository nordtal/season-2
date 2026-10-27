import { useMemo, useState } from "react"

import type {
  Document,
  DocumentList,
  MessageChanges,
  MessageEntry,
  MessageFallback,
  MessageText,
  MessageTexts,
  Warning,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { setDraftValue, useDraft } from "@/lib/drafts"
import {
  currentOf,
  ENGLISH,
  isLanguage,
  languagesByBundle,
  overrideOf,
  shownOf,
  type Language,
} from "@/lib/message-text"
import { useMessageExamples, useMessageFallbacks, useMessageSyntax, useSaveMessageTexts } from "@/lib/queries"
import { branchesOf, formatOf, parse, type Run } from "@/lib/rich-text"
import { textId, textsTree } from "@/lib/settings-tree"
import { pagesOf, pillsOf, type Places } from "@/lib/text-places"
import { Failure } from "@/components/steward/query-state"
import { TERM_ICON } from "@/components/steward/term-icons"
import { Badge } from "@/components/ui/badge"
import { MessageEditor, type MessageDraft } from "@/components/steward/message-editor/message-editor"
import { exampleOf, fillOf, useWords, type Fill } from "@/components/steward/message-editor/examples"
import type { Highlight } from "@/components/steward/settings-view"
import { choice, message, t } from "@/lib/texts"

export const TEXTS_FILE = "texts"

/** One text as a preview draws it: its runs with the cases its examples pick, or its source when it cannot be read. */
type DrawnText = { runs: Run[] | null; text: string; fill: Fill }

/** Draws the text `id`, a `bundle/key`, in `language`. */
type DrawText = (id: string, language: Language) => DrawnText | null

/**
 * The network has no palette of its own. This is one object, so the editor's memo of its tones holds.
 */
const NETWORK_COLOURS: Record<string, string> = {}

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

function changesOf(draft: Record<string, MessageDraft>, byId: Map<string, MessageText>): MessageChanges["changes"] {
  const changes: MessageChanges["changes"] = {}
  for (const [id, changed] of Object.entries(draft)) {
    const entry = byId.get(id)?.entry
    if (!entry) continue
    changes[entry.bundle] = { ...changes[entry.bundle], [entry.key]: changed }
  }
  return changes
}

/** How the open text's editor is drawn: where its previews go, and the language and jump the page holds. */
export type TextEditorOptions = { previewsIn: HTMLElement | null; language: Language; highlight: Highlight | null }

/**
 * The page's texts and documents with their drafts: the tree, how each text is drawn, and how a text is changed, saved
 * and edited.
 */
export function useTextsModel(texts: MessageTexts, documents: DocumentList | undefined, service: string | undefined) {
  const draft = useDraft<MessageDraft>(TEXTS_FILE, isMessageDraftRecord)
  const save = useSaveMessageTexts()
  const takeOver = useSaveMessageTexts()
  const fallbacks = useMessageFallbacks()
  const syntax = useMessageSyntax()
  const examples = useMessageExamples()
  const words = useWords()
  const [warnings, setWarnings] = useState<Warning[]>([])
  const { drafts, setDraft: setDocumentDraft } = useDocumentDrafts()
  const views = useMemo(() => documents?.documents ?? [], [documents])
  const listed = useMemo(
    () => (service === undefined ? texts.texts : texts.texts.filter((text) => text.services.includes(service))),
    [texts.texts, service],
  )
  const nodes = useMemo(() => textsTree(listed, texts.places, views, service), [listed, texts.places, views, service])
  const byId = useMemo(() => new Map(texts.texts.map((text) => [textId(text.entry), text])), [texts.texts])
  const languages = useMemo(
    () =>
      languagesByBundle(
        texts.texts.map((text) => text.entry),
        texts.languages,
      ),
    [texts.texts, texts.languages],
  )
  const colours = service === undefined ? undefined : texts.colours[service]
  const tones = useMemo(
    () => (colours && Object.keys(colours).length > 0 ? colours : (syntax.data?.tones ?? {})),
    [colours, syntax.data],
  )
  const fallenBack = useMemo(() => {
    const found = new Map<string, MessageFallback[]>()
    for (const fallback of fallbacks.data ?? []) {
      const id = `${fallback.bundle}/${fallback.key}`
      found.set(id, [...(found.get(id) ?? []), fallback])
    }
    return found
  }, [fallbacks.data])

  const draftIds = useMemo(() => new Set(Object.keys(draft)), [draft])

  const draw: DrawText = (id, language) => {
    const entry = byId.get(id)?.entry
    if (!entry) return null
    const text = currentOf(entry, draft[id], language) || currentOf(entry, draft[id], ENGLISH)
    const format = formatOf(entry.format)
    const tags = Object.keys(tones)
    const fill = fillOf(entry.args, examples.data, words(language, entry.bundle))
    const runs = parse(text, format, tags)
    const example = (name: string) => exampleOf(name, entry.args, examples.data)
    return { runs: runs && branchesOf(runs, format, tags, example), text, fill }
  }

  function set(id: string, language: Language, value: string[] | null | undefined) {
    const entry = byId.get(id)?.entry
    if (!entry) return
    const saved = overrideOf(entry, language)
    let next = value
    if (Array.isArray(next) && same(next, shownOf(entry, language))) next = undefined
    if (next === null && saved === undefined) next = undefined
    const changed: MessageDraft = { ...draft[id] }
    if (next === undefined) delete changed[language]
    else changed[language] = next
    setDraftValue(TEXTS_FILE, id, Object.keys(changed).length > 0 ? changed : undefined)
  }

  /** Saves the draft of the text `id` alone. */
  function submit(id: string) {
    const changed = draft[id]
    if (!changed) return
    const label = t("steward.settings.texts-saved", { count: Object.keys(changed).length })
    save.mutate(
      { changes: changesOf({ [id]: changed }, byId) },
      {
        onSuccess: (saved) => {
          setDraftValue(TEXTS_FILE, id, undefined)
          setWarnings(saved.warnings)
          announceSave(label, saved.reload)
        },
      },
    )
  }

  /** That writes it over the jar's current text, and its draft is dropped. */
  function takeOverOf(entry: MessageEntry) {
    return (language: Language, override: string[]) =>
      takeOver.mutate(
        { changes: { [entry.bundle]: { [entry.key]: { [language]: override } } } },
        {
          onSuccess: (saved) => {
            set(textId(entry), language, undefined)
            setWarnings(saved.warnings)
            announceSave(t("steward.settings.texts-saved", { count: 1 }), saved.reload)
          },
        },
      )
  }

  const failure = save.error ?? takeOver.error
  const notices = (
    <>
      {failure ? <Failure error={failure} write /> : null}
      {warnings.length > 0 ? (
        <ul className="flex flex-col gap-1 text-xs text-warning">
          {warnings.map((warning, index) => (
            <li key={`${warning.bundle}/${warning.key}/${warning.language}/${index}`}>
              <span className="font-mono">{warning.key}</span> {message(warning.text)}
            </li>
          ))}
        </ul>
      ) : null}
    </>
  )
  const shared: Shared = {
    places: texts.places,
    draw,
    tones,
    languages: documents?.languages ?? [],
    draftIds,
    byId,
    fallenBack,
  }

  const editorFor = (text: MessageText, { previewsIn, language, highlight }: TextEditorOptions) => {
    const id = textId(text.entry)
    return (
      <MessageEditor
        entry={text.entry}
        path={text.path}
        service={service}
        colours={colours ?? NETWORK_COLOURS}
        places={texts.places}
        draft={draft[id]}
        highlight={highlight}
        onChange={(changed, value) => set(id, changed, value)}
        fallbacks={fallenBack.get(id) ?? []}
        onTakeOver={takeOverOf(text.entry)}
        takingOver={takeOver.isPending}
        previews={text.previews}
        previewsIn={previewsIn}
        language={language}
      />
    )
  }

  return {
    nodes,
    draftIds,
    drafts,
    setDocumentDraft,
    languagesOf: (bundle: string) => languages.get(bundle) ?? [ENGLISH],
    submit,
    save,
    notices,
    shared,
    editorFor,
  }
}

export type TextsModel = ReturnType<typeof useTextsModel>

export type Shared = {
  places: Places
  draw: DrawText
  tones: Record<string, string>
  languages: string[]
  draftIds: Set<string>
  byId: Map<string, MessageText>
  fallenBack: Map<string, MessageFallback[]>
}

export function FallenBack() {
  return (
    <TERM_ICON.warning aria-label={t("steward.message-editor.fallen-back")} className="size-4 shrink-0 text-warning" />
  )
}

/** A text's places, and the pages of one of Steward's texts that is on several. */
export function Pills({ entry, places }: { entry: MessageEntry; places: Places }) {
  const pills = pillsOf(entry, places)
  const pages = pagesOf(entry)
  const labels = [
    ...pills.map((pill) =>
      pill.kind === "place"
        ? t("steward.message-editor.place", { place: choice(pill.place) })
        : t("steward.texts.group", { group: choice(pill.surface) }),
    ),
    ...(pages.length > 1 ? pages.map((page) => t("steward.shell.page", { page })) : []),
  ]
  if (labels.length === 0) return null
  return (
    <span aria-label={t("steward.texts.shown-in")} className="flex flex-wrap gap-1">
      {labels.map((label) => (
        <Badge key={label} variant="outline" className="h-4 px-1.5 text-[0.625rem] text-muted-foreground">
          {label}
        </Badge>
      ))}
    </span>
  )
}

/** Keeps the draft of each opened document while the page lives. */
function useDocumentDrafts() {
  const [drafts, setDrafts] = useState<Record<string, Document>>({})
  return {
    drafts,
    setDraft: (id: string, draft: Document | undefined) =>
      setDrafts((all) => {
        const next = { ...all }
        if (draft) next[id] = draft
        else delete next[id]
        return next
      }),
  }
}
