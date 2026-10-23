import { XIcon } from "@phosphor-icons/react"
import { cn } from "cn"
import { TERM_ICON } from "@/components/steward/term-icons"
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"

import type {
  Document,
  DocumentList,
  DocumentView,
  MessageChanges,
  MessageEntry,
  MessageFallback,
  MessageText,
  MessageTexts,
  Warning,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { documentId, documentMatches, documentName, documentOf, languagesOf, type PageCard } from "@/lib/documents"
import {
  currentOf,
  ENGLISH,
  isLanguage,
  languagesByBundle,
  overrideOf,
  shownOf,
  type Language,
} from "@/lib/message-text"
import {
  useGlyphs,
  useMessageExamples,
  useMessageFallbacks,
  useMessageSyntax,
  useSaveMessageTexts,
} from "@/lib/queries"
import { branchesOf, formatOf, parse, type Run } from "@/lib/rich-text"
import { messageLeafMatches, messageName, textId, textsTree } from "@/lib/settings-tree"
import { isAnswer, pagesOf, pillsOf, previewPlacesOf, type Places } from "@/lib/text-places"
import { Failure } from "@/components/steward/query-state"
import { SaveDraft } from "@/components/steward/group-draft"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { MessagePreview } from "@/components/steward/message-preview"
import { MessageEditor, type MessageDraft } from "@/components/steward/message-editor/message-editor"
import { exampleOf, fillOf } from "@/components/steward/message-editor/examples"
import { DocumentPreview } from "@/components/steward/message-editor/document-preview"
import { ComponentCount, DocumentEditor, DocumentMarks } from "@/components/steward/document-editor"
import type { Fill } from "@/components/steward/message-editor/examples"
import { Preview } from "@/components/steward/message-editor/preview"
import { DraftDot, type Highlight, type OutsideSearch, type Target, TreeView } from "@/components/steward/settings-view"
import { choice, message, t } from "@/lib/texts"

export const TEXTS_FILE = "texts"

/** The document or the text open on the page. */
export type Opened = { document?: string; text?: string }

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

/**
 * Every Discord document, game document and text, each drawn as it appears. The one opened shows its editor in its card.
 */
export function TextsForm({
  texts,
  documents,
  service,
  target,
  search,
  opened,
  onOpen,
}: {
  texts: MessageTexts
  documents: DocumentList | undefined
  service: string | undefined
  target: Target | null
  search: OutsideSearch
  opened: Opened
  onOpen: (opened: Opened) => void
}) {
  const draft = useDraft<MessageDraft>(TEXTS_FILE, isMessageDraftRecord)
  const save = useSaveMessageTexts()
  const takeOver = useSaveMessageTexts()
  const fallbacks = useMessageFallbacks()
  const syntax = useMessageSyntax()
  const examples = useMessageExamples()
  const [warnings, setWarnings] = useState<Warning[]>([])
  const [highlight, setHighlight] = useState<Highlight | null>(null)
  const { drafts, setDraft } = useDocumentDrafts()
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
  const count = Object.values(draft).reduce((sum, changed) => sum + Object.keys(changed).length, 0)

  /** A jump from the command palette opens its text. */
  const landed = useRef<number | null>(null)
  useEffect(() => {
    if (!target || landed.current === target.seq) return
    landed.current = target.seq
    onOpen({ text: target.id })
    setHighlight({ id: target.id, seq: target.seq, language: target.language })
  }, [target, onOpen])

  const draw: DrawText = (id, language) => {
    const entry = byId.get(id)?.entry
    if (!entry) return null
    const text = currentOf(entry, draft[id], language) || currentOf(entry, draft[id], ENGLISH)
    const format = formatOf(entry.format)
    const tags = Object.keys(tones)
    const fill = fillOf(entry.args, examples.data, language)
    const runs = parse(text, format, tags)
    const example = (name: string) => exampleOf(name, entry.args, examples.data, language)
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

  function submit() {
    const label = t("steward.settings.texts-saved", { count })
    save.mutate(
      { changes: changesOf(draft, byId) },
      {
        onSuccess: (saved) => {
          clearDraft(TEXTS_FILE)
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
  const above = (
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
  const saveButtons = (
    <SaveDraft
      count={count}
      writable
      pending={save.isPending}
      onSave={submit}
      onDiscard={() => clearDraft(TEXTS_FILE)}
    />
  )
  const shared = {
    places: texts.places,
    draw,
    tones,
    languages: documents?.languages ?? [],
    draftIds,
    byId,
    fallenBack,
  }

  const openText = (text: MessageText) => {
    const id = textId(text.entry)
    return (previewsIn: HTMLElement | null) => (
      <MessageEditor
        entry={text.entry}
        path={text.path}
        service={service}
        languages={languages.get(text.entry.bundle) ?? [ENGLISH]}
        colours={colours ?? NETWORK_COLOURS}
        places={texts.places}
        draft={draft[id]}
        highlight={highlight?.id === id ? highlight : null}
        onChange={(changed, value) => set(id, changed, value)}
        fallbacks={fallenBack.get(id) ?? []}
        onTakeOver={takeOverOf(text.entry)}
        takingOver={takeOver.isPending}
        previews={text.previews}
        previewsIn={previewsIn}
      />
    )
  }

  return (
    <TreeView<PageCard>
      file={TEXTS_FILE}
      nodes={nodes}
      draftIds={draftIds}
      matches={(card, query) =>
        card.kind === "document" ? documentMatches(card.view, query) : messageLeafMatches(card.text.entry, query)
      }
      target={target}
      search={search}
      empty={t("steward.texts.none")}
      above={above}
      collapsed
      cards
      renderLeaf={(leaf) => {
        const card = leaf.value
        const id = card.kind === "document" ? documentId(card.view.declaration) : textId(card.text.entry)
        const open = card.kind === "document" ? opened.document === id : opened.text === id
        return (
          <Card
            key={open ? "open" : "closed"}
            card={card}
            editing={
              !open
                ? undefined
                : card.kind === "document"
                  ? {
                      draft: drafts[id],
                      onDraft: (next) => setDraft(id, next),
                      onClose: () => onOpen({}),
                    }
                  : { editor: openText(card.text), onClose: () => onOpen({}) }
            }
            onOpen={() => onOpen(card.kind === "document" ? { document: id } : { text: id })}
            {...shared}
          />
        )
      }}
      save={saveButtons}
    />
  )
}

type Shared = {
  places: Places
  draw: DrawText
  tones: Record<string, string>
  languages: string[]
  draftIds: Set<string>
  byId: Map<string, MessageText>
  fallenBack: Map<string, MessageFallback[]>
}

function FallenBack() {
  return (
    <TERM_ICON.warning aria-label={t("steward.message-editor.fallen-back")} className="size-4 shrink-0 text-warning" />
  )
}

function cardName(card: PageCard): string {
  return card.kind === "document" ? documentName(card.view.declaration) : messageName(card.text.entry)
}

/** An open document's draft, which the page keeps while another document is open. */
type EditingDocument = {
  draft: Document | undefined
  onDraft: (draft: Document | undefined) => void
  onClose: () => void
}

/** An open text's editor, which draws the text in each of its places into the element it is handed. */
type EditingText = {
  editor: (previewsIn: HTMLElement | null) => ReactNode
  onClose: () => void
}

/**
 * One document or text as it appears, under its name, one card to a row. A click opens it in the card: its name stays
 * on top and its preview in its place, and the editor opens beside the preview from a laptop's width, under it on a
 * phone. A closed card is as wide as the preview column of an open one, so the card opens to the right; the gap between
 * the columns is twice the card's padding and border, which keeps the preview's width.
 */
function Card({
  card,
  editing,
  onOpen,
  ...shared
}: Shared & {
  card: PageCard
  editing?: EditingDocument | EditingText
  onOpen: () => void
}) {
  const name = cardName(card)
  const id = card.kind === "text" ? textId(card.text.entry) : undefined
  const named =
    card.kind === "text"
      ? `text-${transitionName(textId(card.text.entry))}`
      : `document-${transitionName(documentId(card.view.declaration))}`
  // The language one open document is edited and previewed in; every other card shows English.
  const [language, setLanguage] = useState<Language>(ENGLISH)
  const [previewsIn, setPreviewsIn] = useState<HTMLElement | null>(null)
  const draft =
    card.kind === "document" ? (editing && "draft" in editing && editing.draft) || documentOf(card.view) : undefined
  return (
    <article
      aria-label={name}
      data-open={editing ? true : undefined}
      style={{ viewTransitionName: named }}
      className={cn(
        "grid min-w-0 grid-cols-1 items-start gap-3 rounded-lg border p-3 [view-transition-class:translation-card]",
        // Stacked, an open card steps out of the list's indent to the page's width; side by side, its preview keeps its place.
        editing ? "max-lg:-ml-(--list-indent) lg:grid-cols-2 lg:gap-x-[calc(1.5rem+2px)]" : "lg:w-1/2",
      )}
    >
      <div className="flex min-h-7 min-w-0 items-start gap-2 lg:col-span-full">
        <div className="flex min-h-7 min-w-0 grow flex-wrap items-center gap-2">
          <h3 className="min-w-0 grow basis-28 truncate text-sm font-medium">{name}</h3>
          {card.kind === "text" ? (
            <Pills entry={card.text.entry} places={shared.places} />
          ) : (
            <DocumentMarks view={card.view} />
          )}
          {card.kind === "text" && !card.text.entry.inBundle ? (
            <Badge variant="outline" className="shrink-0 text-muted-foreground">
              {t("steward.settings.not-in-bundle")}
            </Badge>
          ) : null}
          {id !== undefined && shared.fallenBack.has(id) ? <FallenBack /> : null}
          {id !== undefined && shared.draftIds.has(id) ? <DraftDot /> : null}
          {editing && card.kind === "document" && draft ? (
            <ComponentCount view={card.view} draft={draft} language={language} />
          ) : null}
        </div>
        {editing ? (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("steward.document-editor.close")}
            onClick={editing.onClose}
          >
            <XIcon aria-hidden />
          </Button>
        ) : null}
      </div>
      {!editing ? (
        <button type="button" aria-label={name} onClick={onOpen} className="min-w-0 text-left">
          {card.kind === "document" ? (
            <ShippedPreview view={card.view} language={ENGLISH} />
          ) : (
            <TextPreview entry={card.text.entry} {...shared} />
          )}
        </button>
      ) : (
        <div ref={setPreviewsIn} className="min-w-0 lg:sticky lg:top-[calc(var(--content-top,0px)+1rem)]">
          {card.kind === "document" && draft ? (
            <DocumentPreview
              declaration={card.view.declaration}
              game={card.view.game}
              document={draft}
              language={language}
            />
          ) : null}
        </div>
      )}
      {editing && "draft" in editing && card.kind === "document" && draft ? (
        <DocumentEditor
          view={card.view}
          draft={draft}
          // The network's languages and any the document is written in beyond them.
          languages={[...new Set([...languagesOf(draft), ...shared.languages])]}
          language={language}
          onLanguage={setLanguage}
          onDraft={(next) =>
            editing.onDraft(next && JSON.stringify(next) !== JSON.stringify(documentOf(card.view)) ? next : undefined)
          }
        />
      ) : null}
      {editing && "editor" in editing ? <div className="min-w-0">{editing.editor(previewsIn)}</div> : null}
    </article>
  )
}

/** A view transition's name is an identifier, so every other character of an id becomes a hyphen. */
function transitionName(id: string): string {
  return id.replace(/[^A-Za-z0-9_-]/g, "-")
}

function ShippedPreview({ view, language }: { view: DocumentView; language: Language }) {
  return (
    <DocumentPreview declaration={view.declaration} game={view.game} document={documentOf(view)} language={language} />
  )
}

/** A text of a bundle: its text in the first place it appears, as the editor's preview draws it. */
function TextPreview({ entry, language = ENGLISH, ...shared }: Shared & { entry: MessageEntry; language?: Language }) {
  const glyphs = useGlyphs()
  const drawn = shared.draw(textId(entry), language)
  if (!drawn) return null
  if (!drawn.runs) return <MessagePreview text={drawn.text} format={entry.format} className="text-xs" />
  const place = previewPlacesOf(entry)[0]
  return (
    <Preview
      runs={drawn.runs}
      shown={place}
      surface={place === undefined ? undefined : shared.places[place]}
      fill={drawn.fill}
      glyphs={glyphs.data ?? []}
      tones={shared.tones}
      answer={isAnswer(entry)}
    />
  )
}

/** A text's places, and the pages of one of Steward's texts that is on several. */
function Pills({ entry, places }: { entry: MessageEntry; places: Places }) {
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
