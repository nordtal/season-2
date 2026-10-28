import { useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { ArrowLeftIcon, CaretRightIcon } from "@phosphor-icons/react"
import { cn } from "@/lib/utils"

import type { BlockUses, Document, DocumentList, DocumentPart, MessageTexts } from "@/lib/api"
import { Empty, QueryState } from "@/components/steward/query-state"
import { setDraftValue } from "@/lib/drafts"
import { documentId, documentOf, sharedPartId, type PageCard } from "@/lib/documents"
import { ENGLISH, type Language } from "@/lib/message-text"
import { useTranslationSearch } from "@/lib/queries"
import { choice, t } from "@/lib/texts"
import { isBuildingBlock } from "@/lib/text-places"
import {
  type Area,
  areasOf,
  documentWords,
  hitItemId,
  type Item,
  type Section,
  untranslated,
} from "@/lib/translation-items"
import { ComponentCount, DocumentEditor, DocumentMarks } from "@/components/steward/document-editor"
import { SharedPartEditor } from "@/components/steward/shared-parts"
import { argsOf, LanguageToggle } from "@/components/steward/document-fields"
import { SearchField } from "@/components/steward/filter-bar"
import { DocumentPreview } from "@/components/steward/message-editor/document-preview"
import { OpenBlock } from "@/components/steward/message-editor/preview"
import { DraftDot, type Highlight, PinnedRow, type Target, useWide } from "@/components/steward/settings-view"
import { FallenBack, Pills, TEXTS_FILE, type TextsModel, useTextsModel } from "@/components/steward/texts"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"

/** Where the page stands: the area listed and the document or text open, each by its id. */
export type TranslationsState = { area?: string; document?: string; text?: string }

/** An item with the area and section it is listed in. */
type Placed = Item & { area: Area; section: Section }

/** Which building blocks the list shows, by how many texts and documents use each. */
const BLOCK_FILTERS = ["ALL", "SHARED", "ONE"] as const
type BlockFilter = (typeof BLOCK_FILTERS)[number]

const NO_USES: BlockUses = { texts: [], documents: [] }

/** Returns the building block's key that an item is, or undefined for any other item. */
function blockKey(item: Item): string | undefined {
  return item.card.kind === "text" && isBuildingBlock(item.card.text.entry) ? item.card.text.entry.key : undefined
}

function totalOf(uses: BlockUses): number {
  return uses.texts.length + uses.documents.length
}

function kept(filter: BlockFilter, count: number): boolean {
  return filter === "ALL" || (filter === "SHARED" ? count > 1 : count === 1)
}

/**
 * Every document and text, listed area by area and then section by section. One item opens at a time: on a phone in place
 * of the list, from a laptop's width beside it. One language holds for the list and the open item.
 */
export function TranslationsView({
  texts,
  documents,
  service,
  serviceFilter,
  state,
  onState,
  target,
}: {
  texts: MessageTexts
  documents: DocumentList | undefined
  service: string | undefined
  serviceFilter: ReactNode
  state: TranslationsState
  onState: (next: TranslationsState) => void
  target: Target | null
}) {
  const model = useTextsModel(texts, documents, service)
  const [language, setLanguage] = useState<Language>(ENGLISH)
  const [query, setQuery] = useState("")
  const [highlight, setHighlight] = useState<Highlight | null>(null)
  const [blockFilter, setBlockFilter] = useState<BlockFilter>("ALL")
  const areas = useMemo(() => areasOf(model.nodes), [model.nodes])
  const placed = useMemo(
    () =>
      areas.flatMap((area) =>
        area.sections.flatMap((section) => section.items.map((item): Placed => ({ ...item, area, section }))),
      ),
    [areas],
  )
  const { languagesOf } = model
  // Every language the network names, a document holds or a bundle ships.
  const languages = useMemo(
    () => [
      ...new Set([
        ENGLISH,
        ...texts.languages,
        ...model.shared.languages,
        ...texts.texts.flatMap((text) => languagesOf(text.entry.bundle)),
      ]),
    ],
    [texts.languages, texts.texts, model.shared.languages, languagesOf],
  )
  const searched = useTranslationSearch(query)

  const openId = state.document ?? state.text
  const open = openId === undefined ? undefined : placed.find((item) => item.id === openId)
  // A link that opens an item lists the area it is in.
  const area = areas.find((each) => each.id === (state.area ?? open?.area.id))

  // A jump from the command palette opens its text or document in the language it was found in.
  const [seen, setSeen] = useState<number | null>(null)
  if (target && seen !== target.seq) {
    setSeen(target.seq)
    setQuery("")
    if (target.language) setLanguage(target.language)
    setHighlight({ id: target.id, seq: target.seq, language: target.language })
  }
  const landed = useRef<number | null>(null)
  useEffect(() => {
    if (!highlight || landed.current === highlight.seq) return
    const item = placed.find((each) => each.id === highlight.id)
    if (!item) return
    landed.current = highlight.seq
    onState(openState(item))
  }, [highlight, placed, onState])
  useEffect(() => {
    if (!highlight) return undefined
    const timeout = window.setTimeout(() => setHighlight(null), 2400)
    return () => window.clearTimeout(timeout)
  }, [highlight])

  const page: Page = {
    model,
    language,
    open,
    onOpen: (item) => onState(openState(item)),
    changed: (item) => (item.card.kind === "text" ? model.draftIds.has(item.id) : model.drafts[item.id] !== undefined),
    missing: (item) => untranslated(item.card, language, model.languagesOf),
    uses: (item) => {
      if (item.card.kind === "shared") return { texts: [], documents: item.card.shared.documents }
      const key = blockKey(item)
      return key === undefined ? undefined : (texts.uses[key] ?? NO_USES)
    },
    find: (id) => placed.find((item) => item.id === id) ?? id,
  }
  const openBlock = (key: string) => {
    const block = placed.find((item) => blockKey(item) === key || item.id === key)
    return block && (() => page.onOpen(block))
  }
  // One toggle at a time: beside the filter from a laptop's width, else in the open item's header.
  const wide = useWide()
  const toggle = <LanguageToggle languages={languages} language={language} onLanguage={setLanguage} />
  return (
    <div className="flex flex-col gap-4">
      {model.notices}
      <div className="grid min-w-0 grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
        <div
          className={cn(
            "flex min-w-0 flex-col gap-3 lg:sticky lg:top-[calc(var(--content-top,0px)+(--spacing(4)))] lg:max-h-[calc(100dvh-var(--content-top,0px)-var(--content-bottom,0px)-(--spacing(8)))] lg:overflow-y-auto lg:p-0.5",
            open && "max-lg:hidden",
          )}
        >
          <div className="flex min-w-0">
            <SearchField value={query} onValueChange={setQuery} label={t("steward.texts.search")} />
          </div>
          <div className="flex min-w-0 items-center gap-2">
            {serviceFilter}
            {wide || !open ? toggle : null}
          </div>
          {areas.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("steward.texts.none")}</p>
          ) : query.trim() !== "" ? (
            <QueryState query={searched} rows={4}>
              {(hits) => {
                const ids = new Set(hits.map(hitItemId))
                return <Found page={page} areas={areas} found={placed.filter((item) => ids.has(item.id))} />
              }}
            </QueryState>
          ) : area ? (
            <div className="flex min-w-0 flex-col gap-4">
              <div className="flex min-w-0 items-center gap-1">
                <Back
                  label={t("steward.texts.areas")}
                  onBack={() => onState({ area: undefined, document: undefined, text: undefined })}
                />
                <h2 className="min-w-0 flex-1 truncate text-base font-semibold">{area.label}</h2>
                <Count count={area.count} />
              </div>
              {area.id === "VALUES" ? (
                <Tabs value={blockFilter} onValueChange={(next: BlockFilter) => setBlockFilter(next)}>
                  <TabsList aria-label={t("steward.texts.block-filter-label")}>
                    {BLOCK_FILTERS.map((filter) => (
                      <TabsTrigger key={filter} value={filter} className="flex-none px-2.5 text-xs">
                        {t("steward.texts.block-filter", { filter: choice(filter) })}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </Tabs>
              ) : null}
              {sectionsShown(area, blockFilter, page).map((section) => (
                <section key={section.id} aria-label={section.label || area.label} className="flex flex-col gap-2">
                  {section.label === "" ? null : (
                    <h3 className="flex items-center gap-2 px-1 text-sm font-medium">
                      <span className="min-w-0 truncate">{section.label}</span>
                      <Count count={section.items.length} />
                      <UntranslatedCount count={section.items.filter(page.missing).length} />
                    </h3>
                  )}
                  <ItemList>
                    {section.items.map((item) => (
                      <ItemRow key={item.id} page={page} item={{ ...item, area, section }} />
                    ))}
                  </ItemList>
                </section>
              ))}
            </div>
          ) : (
            <AreaList page={page} areas={areas} onArea={(id) => onState({ area: id })} />
          )}
        </div>
        {open ? (
          <article aria-label={open.name} className="flex min-w-0 flex-col gap-4">
            <div className="flex min-w-0 flex-col gap-1.5">
              <div className="flex min-w-0 items-center gap-2">
                <Back
                  label={t("steward.texts.back")}
                  onBack={() => onState({ area: open.area.id, document: undefined, text: undefined })}
                  className="lg:hidden"
                />
                <h2 className="min-w-0 flex-1 truncate text-lg font-semibold">{open.name}</h2>
                {wide ? null : toggle}
              </div>
              <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                <Trail labels={[open.area.label, open.section.label, ...open.trail].filter((label) => label !== "")} />
                <Marks page={page} item={open} />
              </div>
            </div>
            <Links
              label={t("steward.texts.used-in")}
              links={(() => {
                const uses = page.uses(open)
                return uses ? [...uses.texts, ...uses.documents].map(page.find) : []
              })()}
              onOpen={page.onOpen}
            />
            <OpenBlock value={openBlock}>
              <ItemEditor
                key={open.id}
                page={page}
                item={open}
                highlight={highlight?.id === open.id ? highlight : null}
              />
            </OpenBlock>
          </article>
        ) : (
          <div className="max-lg:hidden">
            <Empty title={t("steward.texts.none-open")} note={t("steward.texts.none-open-note")} />
          </div>
        )}
      </div>
    </div>
  )
}

/** What the list's rows and the open item share. */
type Page = {
  model: TextsModel
  language: Language
  open: Placed | undefined
  onOpen: (item: Placed) => void
  changed: (item: Item) => boolean
  missing: (item: Item) => boolean
  /** A building block's uses, or undefined for any other item. */
  uses: (item: Item) => BlockUses | undefined
  /** The item listed under `id`, or `id` where the list holds none. */
  find: (id: string) => Placed | string
}

/** Returns an area's sections, in the Building blocks those the filter keeps, without the sections it empties. */
function sectionsShown(area: Area, filter: BlockFilter, page: Page): Section[] {
  if (area.id !== "VALUES" || filter === "ALL") return area.sections
  return area.sections
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => kept(filter, totalOf(page.uses(item) ?? NO_USES))),
    }))
    .filter((section) => section.items.length > 0)
}

/** A row of links to other items, each opening its item; an id the list does not hold is shown as written. */
function Links({
  label,
  links,
  onOpen,
}: {
  label: string
  links: (Placed | string)[]
  onOpen: (item: Placed) => void
}) {
  if (links.length === 0) return null
  return (
    <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      <ul aria-label={label} className="flex min-w-0 flex-wrap gap-1">
        {links.map((link) => (
          <li key={typeof link === "string" ? link : link.id} className="min-w-0">
            {typeof link === "string" ? (
              <span className="text-xs text-muted-foreground">{link}</span>
            ) : (
              <Button type="button" variant="outline" size="xs" onClick={() => onOpen(link)} className="max-w-full">
                <span className="truncate">{link.name}</span>
              </Button>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}

function openState(item: Placed): TranslationsState {
  return item.card.kind !== "text"
    ? { area: item.area.id, document: item.id, text: undefined }
    : { area: item.area.id, document: undefined, text: item.id }
}

/** A shared part's draft, or the part saved. */
function sharedPartFor(model: TextsModel, card: PageCard & { kind: "shared" }): DocumentPart {
  return model.drafts[sharedPartId(card.shared.name)]?.parts[0] ?? card.shared.part
}

/** A document's draft, or its saved document. */
function documentFor(model: TextsModel, card: PageCard & { kind: "document" }): Document {
  return model.drafts[documentId(card.view.declaration)] ?? documentOf(card.view)
}

/** An item's first words in `language`, else in English, as a preview draws them. */
function lineOf(model: TextsModel, card: PageCard, language: Language): string {
  if (card.kind === "text") {
    return model.shared.draw(`${card.text.entry.bundle}/${card.text.entry.key}`, language) ?? ""
  }
  const document = card.kind === "document" ? documentFor(model, card) : { parts: [sharedPartFor(model, card)] }
  const own = documentWords(document, language)
  const shownIn = own ? language : ENGLISH
  const source = own || documentWords(document, ENGLISH)
  if (!source) return ""
  const game = card.kind === "document" && card.view.game
  const args = card.kind === "document" ? argsOf(card.view.named) : []
  return model.shared.drawLine(source, game ? "MINIMESSAGE" : "DISCORD_MARKDOWN", args, shownIn)
}

function Trail({ labels }: { labels: string[] }) {
  if (labels.length === 0) return null
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1 text-xs text-muted-foreground">
      {labels.map((label, index) => (
        <span key={`${label}-${index}`} className="flex min-w-0 items-center gap-1">
          {index > 0 ? <CaretRightIcon aria-hidden className="size-3 shrink-0" /> : null}
          <span className="truncate">{label}</span>
        </span>
      ))}
    </span>
  )
}

function Untranslated() {
  return (
    <Badge variant="outline" className="shrink-0 text-warning">
      {t("steward.texts.untranslated")}
    </Badge>
  )
}

function UntranslatedCount({ count }: { count: number }) {
  if (count === 0) return null
  return (
    <Badge variant="outline" className="shrink-0 text-warning tabular-nums">
      {t("steward.texts.untranslated-count", { count })}
    </Badge>
  )
}

function Count({ count }: { count: number }) {
  return <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{count}</span>
}

function ItemList({ children }: { children: ReactNode }) {
  return <ul className="flex flex-col divide-y overflow-hidden rounded-lg border">{children}</ul>
}

/** One item in a list: its name, the branches above it, its first words in the page's language and its marks. */
function ItemRow({ page, item, trail = item.trail }: { page: Page; item: Placed; trail?: string[] }) {
  const current = page.open?.id === item.id
  const row = useRef<HTMLButtonElement>(null)
  // A link or a jump opens an item far down the list, so its row comes into view; a click on a visible row moves nothing.
  useEffect(() => {
    if (current) row.current?.scrollIntoView({ block: "nearest" })
  }, [current])
  return (
    <li>
      <button
        ref={row}
        type="button"
        aria-label={item.name}
        aria-current={current || undefined}
        onClick={() => page.onOpen(item)}
        className="flex w-full min-w-0 items-center gap-3 px-3 py-2.5 text-left outline-none hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring aria-[current=true]:bg-muted pointer-coarse:min-h-12"
      >
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span className="min-w-0 text-sm font-medium wrap-break-word">{item.name}</span>
            {item.card.kind === "document" ? <DocumentMarks view={item.card.view} /> : null}
            {page.changed(item) ? <DraftDot /> : null}
            {page.model.shared.fallenBack.has(item.id) ? <FallenBack /> : null}
          </span>
          <Trail labels={trail} />
          <span className="truncate text-xs text-muted-foreground">{lineOf(page.model, item.card, page.language)}</span>
        </span>
        {page.missing(item) ? <Untranslated /> : null}
        <UseCount uses={page.uses(item)} />
        <CaretRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
      </button>
    </li>
  )
}

function UseCount({ uses }: { uses: BlockUses | undefined }) {
  if (!uses) return null
  return (
    <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
      {t("steward.texts.uses-count", { count: totalOf(uses) })}
    </span>
  )
}

function AreaList({ page, areas, onArea }: { page: Page; areas: Area[]; onArea: (area: string) => void }) {
  return (
    <ul aria-label={t("steward.texts.areas")} className="flex flex-col divide-y overflow-hidden rounded-lg border">
      {areas.map((area) => {
        const items = area.sections.flatMap((section) => section.items)
        return (
          <li key={area.id}>
            <button
              type="button"
              onClick={() => onArea(area.id)}
              className="flex w-full items-center gap-3 px-3 py-3 text-left outline-none hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-ring pointer-coarse:min-h-12"
            >
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{area.label}</span>
              {items.some(page.changed) ? <DraftDot /> : null}
              <UntranslatedCount count={items.filter(page.missing).length} />
              <Count count={area.count} />
              <CaretRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
            </button>
          </li>
        )
      })}
    </ul>
  )
}

/** The items the search found, under their areas, each with its section in its trail. */
function Found({ page, areas, found }: { page: Page; areas: Area[]; found: Placed[] }) {
  if (found.length === 0) return <p className="text-sm text-muted-foreground">{t("steward.settings.no-match")}</p>
  return (
    <div className="flex flex-col gap-4">
      {areas.map((area) => {
        const inArea = found.filter((item) => item.area.id === area.id)
        if (inArea.length === 0) return null
        return (
          <section key={area.id} aria-label={area.label} className="flex flex-col gap-2">
            <h2 className="flex items-center gap-2 px-1 text-sm font-medium">
              {area.label}
              <Count count={inArea.length} />
            </h2>
            <ItemList>
              {inArea.map((item) => (
                <ItemRow
                  key={item.id}
                  page={page}
                  item={item}
                  trail={[item.section.label, ...item.trail].filter((label) => label !== "")}
                />
              ))}
            </ItemList>
          </section>
        )
      })}
    </div>
  )
}

function Marks({ page, item }: { page: Page; item: Placed }) {
  const card = item.card
  return (
    <>
      {card.kind === "shared" ? null : card.kind === "text" ? (
        isBuildingBlock(card.text.entry) ? null : (
          <Pills entry={card.text.entry} places={page.model.shared.places} />
        )
      ) : (
        <>
          <DocumentMarks view={card.view} />
          <ComponentCount view={card.view} draft={documentFor(page.model, card)} language={page.language} />
        </>
      )}
      {card.kind === "text" && !card.text.entry.inBundle ? (
        <Badge variant="outline" className="shrink-0 text-muted-foreground">
          {t("steward.settings.not-in-bundle")}
        </Badge>
      ) : null}
      {page.model.shared.fallenBack.has(item.id) ? <FallenBack /> : null}
      {page.changed(item) ? <DraftDot /> : null}
      {page.missing(item) ? <Untranslated /> : null}
    </>
  )
}

/**
 * The open item's preview over its editor, in the page's language. A text's bar discards and saves that text alone; a
 * document's editor has its own.
 */
function ItemEditor({ page, item, highlight }: { page: Page; item: Placed; highlight: Highlight | null }) {
  const { model, language } = page
  const [previewsIn, setPreviewsIn] = useState<HTMLElement | null>(null)
  const card = item.card
  if (card.kind === "shared") {
    return (
      <SharedPartEditor
        shared={card.shared}
        draft={sharedPartFor(model, card)}
        language={language}
        onDraft={(next) =>
          model.setDocumentDraft(
            item.id,
            next && JSON.stringify(next) !== JSON.stringify(card.shared.part) ? { parts: [next] } : undefined,
          )
        }
      />
    )
  }
  if (card.kind === "document") {
    const draft = documentFor(model, card)
    return (
      <div className="flex min-w-0 flex-col gap-4">
        <DocumentPreview
          declaration={card.view.declaration}
          game={card.view.game}
          document={draft}
          language={language}
        />
        <DocumentEditor
          view={card.view}
          draft={draft}
          language={language}
          onDraft={(next) =>
            model.setDocumentDraft(
              item.id,
              next && JSON.stringify(next) !== JSON.stringify(documentOf(card.view)) ? next : undefined,
            )
          }
        />
      </div>
    )
  }
  const id = item.id
  const shown = model.languagesOf(card.text.entry.bundle).includes(language) ? language : ENGLISH
  const unchanged = !model.draftIds.has(id) || model.save.isPending
  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div ref={setPreviewsIn} className="flex min-w-0 flex-col gap-2" />
      <div className="flex min-w-0 flex-col gap-4">
        {model.editorFor(card.text, { previewsIn, language: shown, highlight })}
        <PinnedRow>
          <div className="pointer-events-auto flex min-w-0 flex-1 items-center justify-end gap-1 rounded-md border bg-card/95 p-1 backdrop-blur md:gap-2 md:p-2">
            <Button
              type="button"
              variant="ghost"
              disabled={unchanged}
              onClick={() => setDraftValue(TEXTS_FILE, id, undefined)}
            >
              {t("steward.document-editor.discard")}
            </Button>
            <Button type="button" disabled={unchanged} onClick={() => model.submit(id)}>
              {t("steward.document-editor.save")}
            </Button>
          </div>
        </PinnedRow>
      </div>
    </div>
  )
}

function Back({ label, onBack, className }: { label: string; onBack: () => void; className?: string }) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      aria-label={label}
      title={label}
      onClick={onBack}
      className={cn("-ml-2", className)}
    >
      <ArrowLeftIcon aria-hidden />
    </Button>
  )
}
