import { CaretRightIcon } from "@phosphor-icons/react"
import { TERM_ICON } from "@/components/steward/term-icons"
import { useEffect, useId, useMemo, useRef, useState, type ReactNode } from "react"
import { cn } from "cn"

import type {
  Layouts,
  MessageChanges,
  MessageEntry,
  MessageFallback,
  MessageText,
  MessageTexts,
  Warning,
} from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { holderOf, layoutId, layoutName, layoutOf, type PageLayout } from "@/lib/layouts"
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
import { branchesOf, formatOf, parse } from "@/lib/rich-text"
import { humanise, messageLeafMatches, messageName, textId, textsTree } from "@/lib/settings-tree"
import { groupOf, pillsOf, previewPlacesOf, type Places } from "@/lib/text-places"
import { Failure } from "@/components/steward/query-state"
import { SaveDraft } from "@/components/steward/group-draft"
import { Badge } from "@/components/ui/badge"
import { MessagePreview } from "@/components/steward/message-preview"
import { MessageEditor, type MessageDraft } from "@/components/steward/message-editor/message-editor"
import { exampleOf } from "@/components/steward/message-editor/examples"
import { DiscordLayoutPreview, type DrawText } from "@/components/steward/message-editor/layout-preview"
import { Preview } from "@/components/steward/message-editor/preview"
import {
  DraftDot,
  PinnedRow,
  type Highlight,
  type OutsideSearch,
  type Target,
  TreeView,
} from "@/components/steward/settings-view"
import { choice, message, t } from "@/lib/texts"

export const TEXTS_FILE = "texts"

/** The layout open on the page, or the text open as a layout of one part when `layout` is absent. */
export type Opened = { layout?: string; text?: string }

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
 * Every text as the layouts that show it, each drawn as it appears. An opened layout shows its preview beside the editor of the picked text.
 */
export function TextsForm({
  texts,
  layouts,
  layoutsSettled,
  service,
  target,
  search,
  opened,
  onOpen,
  header,
}: {
  texts: MessageTexts
  layouts: Layouts | undefined
  /** Whether the layouts arrived or failed, so a jump waits to find the layout that holds its text. */
  layoutsSettled: boolean
  service: string | undefined
  target: Target | null
  search: OutsideSearch
  opened: Opened
  onOpen: (opened: Opened) => void
  /** The page's title row, drawn over the list and not over an open layout. */
  header: ReactNode
}) {
  const draft = useDraft<MessageDraft>(TEXTS_FILE, isMessageDraftRecord)
  const save = useSaveMessageTexts()
  const takeOver = useSaveMessageTexts()
  const fallbacks = useMessageFallbacks()
  const syntax = useMessageSyntax()
  const examples = useMessageExamples()
  const [warnings, setWarnings] = useState<Warning[]>([])
  const [highlight, setHighlight] = useState<Highlight | null>(null)
  // A jump's language holds until a tab is picked after it.
  const [chosen, setChosen] = useState<{ language: Language; seq?: number }>({ language: ENGLISH })
  const layoutLanguage = highlight?.language && highlight.seq !== chosen.seq ? highlight.language : chosen.language
  const views = useMemo(() => layouts?.layouts ?? [], [layouts])
  const listed = useMemo(
    () => (service === undefined ? texts.texts : texts.texts.filter((text) => text.services.includes(service))),
    [texts.texts, service],
  )
  const nodes = useMemo(() => textsTree(listed, texts.places, views), [listed, texts.places, views])
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

  /** A jump from the command palette opens the layout that holds its text, once the layouts are known. */
  const landed = useRef<number | null>(null)
  useEffect(() => {
    if (!target || !layoutsSettled || landed.current === target.seq) return
    landed.current = target.seq
    const holder = holderOf(views, target.id)
    onOpen(holder ? { layout: layoutId(layoutOf(holder)), text: target.id } : { text: target.id })
    setHighlight({ id: target.id, seq: target.seq, language: target.language })
  }, [target, layoutsSettled, views, onOpen])

  const draw: DrawText = (id, language, values) => {
    const entry = byId.get(id)?.entry
    if (!entry) return null
    const text = currentOf(entry, draft[id], language) || currentOf(entry, draft[id], ENGLISH)
    const format = formatOf(entry.format)
    const tags = Object.keys(tones)
    const fill = (name: string) => values?.[name] ?? exampleOf(name, entry.args, examples.data, language)
    const runs = parse(text, format, tags)
    return { runs: runs && branchesOf(runs, format, tags, fill), text, fill }
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
          announceSave(label, saved.reload, "")
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
            announceSave(t("steward.settings.texts-saved", { count: 1 }), saved.reload, "")
          },
        },
      )
  }

  const failure = save.error ?? takeOver.error
  const above = (
    <>
      {failure ? <Failure error={failure} /> : null}
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
      writable={texts.writable}
      pending={save.isPending}
      onSave={submit}
      onDiscard={() => clearDraft(TEXTS_FILE)}
    />
  )
  const shared = {
    places: texts.places,
    draw,
    tones,
    languages: layouts?.languages ?? [],
    tiers: layouts?.tiers ?? [],
    draftIds,
    byId,
    fallenBack,
  }

  const view =
    opened.layout === undefined ? undefined : views.find((each) => layoutId(layoutOf(each)) === opened.layout)
  const open = view ? { kind: "layout" as const, view } : byId.get(opened.text ?? "")
  if (open) {
    const card: PageLayout = "kind" in open ? open : { kind: "text", text: open }
    const ids = card.kind === "layout" ? card.view.entry.shows.filter((id) => byId.has(id)) : [textId(card.text.entry)]
    const picked = opened.text !== undefined && ids.includes(opened.text) ? opened.text : ids[0]
    const text = picked === undefined ? undefined : byId.get(picked)
    return (
      <LayoutPage
        card={card}
        ids={ids}
        picked={picked}
        onPick={(id) => onOpen({ ...opened, text: id })}
        onClose={() => onOpen({})}
        above={above}
        save={saveButtons}
        {...shared}
        language={layoutLanguage}
        editor={
          text ? (
            <MessageEditor
              key={picked}
              entry={text.entry}
              path={text.path}
              service={service}
              languages={languages.get(text.entry.bundle) ?? [ENGLISH]}
              colours={colours ?? NETWORK_COLOURS}
              places={texts.places}
              writable={texts.writable}
              draft={draft[textId(text.entry)]}
              highlight={highlight?.id === picked ? highlight : null}
              onChange={(changed, value) => set(textId(text.entry), changed, value)}
              fallbacks={fallenBack.get(textId(text.entry)) ?? []}
              onTakeOver={takeOverOf(text.entry)}
              takingOver={takeOver.isPending}
              previews={text.previews}
              language={card.kind === "layout" ? layoutLanguage : undefined}
              onLanguage={
                card.kind === "layout" ? (next) => setChosen({ language: next, seq: highlight?.seq }) : undefined
              }
              placePreviews={card.kind === "text"}
            />
          ) : null
        }
      />
    )
  }

  return (
    <>
      {header}
      <TreeView<PageLayout>
        file={TEXTS_FILE}
        nodes={nodes}
        draftIds={draftIds}
        matches={(card, query) => idsOf(card).some((id) => matchesId(byId, id, query))}
        notice={texts.writable ? null : t("steward.settings.read-only")}
        target={null}
        search={search}
        empty={t("steward.texts.none")}
        above={above}
        collapsed
        cards
        renderLeaf={(leaf) => (
          <LayoutCard
            card={leaf.value}
            query={search.query}
            onOpen={(id) =>
              onOpen(
                leaf.value.kind === "layout" ? { layout: layoutId(layoutOf(leaf.value.view)), text: id } : { text: id },
              )
            }
            {...shared}
          />
        )}
        save={saveButtons}
      />
    </>
  )
}

function idsOf(card: PageLayout): string[] {
  return card.kind === "layout" ? card.view.entry.shows : [textId(card.text.entry)]
}

function matchesId(byId: Map<string, MessageText>, id: string, query: string): boolean {
  const entry = byId.get(id)?.entry
  return entry !== undefined && messageLeafMatches(entry, query)
}

type Shared = {
  places: Places
  draw: DrawText
  tones: Record<string, string>
  languages: string[]
  tiers: Layouts["tiers"]
  draftIds: Set<string>
  byId: Map<string, MessageText>
  fallenBack: Map<string, MessageFallback[]>
}

function FallenBack() {
  return (
    <TERM_ICON.warning aria-label={t("steward.message-editor.fallen-back")} className="size-4 shrink-0 text-warning" />
  )
}

function cardName(card: PageLayout): string {
  return card.kind === "layout" ? layoutName(layoutOf(card.view)) : messageName(card.text.entry)
}

/**
 * One layout as it appears, under its name. Each text of a declared layout opens it with that text picked; a layout of one part opens as a whole.
 */
function LayoutCard({
  card,
  query,
  onOpen,
  ...shared
}: Shared & { card: PageLayout; query: string; onOpen: (id: string) => void }) {
  const name = cardName(card)
  const drafted = idsOf(card).some((id) => shared.draftIds.has(id))
  const found =
    card.kind === "layout" && query.trim()
      ? card.view.entry.shows.filter((id) => matchesId(shared.byId, id, query))
      : []
  return (
    <article aria-label={name} className="flex min-w-0 flex-col gap-2 rounded-lg border p-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <h3 className="min-w-0 flex-1 truncate text-sm font-medium">{name}</h3>
        {card.kind === "text" ? <Pills entry={card.text.entry} places={shared.places} /> : null}
        {card.kind === "text" && !card.text.entry.inBundle ? (
          <Badge variant="outline" className="shrink-0 text-muted-foreground">
            {t("steward.settings.not-in-bundle")}
          </Badge>
        ) : null}
        {idsOf(card).some((id) => shared.fallenBack.has(id)) ? <FallenBack /> : null}
        {drafted ? <DraftDot /> : null}
      </div>
      {card.kind === "layout" ? (
        <DiscordLayoutPreview
          layout={layoutOf(card.view)}
          languages={shared.languages}
          tiers={shared.tiers}
          draw={shared.draw}
          onPick={onOpen}
        />
      ) : (
        <button
          type="button"
          aria-label={name}
          onClick={() => onOpen(textId(card.text.entry))}
          className="min-w-0 text-left"
        >
          <TextPreview entry={card.text.entry} {...shared} />
        </button>
      )}
      {found.length > 0 ? (
        <div className="flex flex-wrap gap-1">
          {found.map((id) => {
            const entry = shared.byId.get(id)?.entry
            return entry ? (
              <Badge key={id} asChild variant="secondary">
                <button type="button" onClick={() => onOpen(id)}>
                  {messageName(entry)}
                </button>
              </Badge>
            ) : null
          })}
        </div>
      ) : null}
    </article>
  )
}

/** A layout of one part: its text in the first place it appears, as the editor's preview draws it. */
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
    />
  )
}

/**
 * An open layout: where it is listed, its preview, and beside it the editor of the picked text and the list of its texts. A phone stacks them.
 */
function LayoutPage({
  card,
  ids,
  picked,
  onPick,
  onClose,
  editor,
  above,
  save,
  language,
  ...shared
}: Shared & {
  card: PageLayout
  ids: string[]
  picked: string | undefined
  onPick: (id: string) => void
  onClose: () => void
  language: Language
  editor: ReactNode
  above: ReactNode
  save: ReactNode
}) {
  const textsHeading = useId()
  const layout = card.kind === "layout" ? layoutOf(card.view) : undefined
  const group = card.kind === "layout" ? layoutOf(card.view).surface : groupOf(card.text.entry, shared.places)
  const area =
    card.kind === "layout"
      ? layoutOf(card.view).area
      : (card.text.entry.section[0] ?? humanise(card.text.entry.key.split(".")[0]))
  const entry = picked === undefined ? undefined : shared.byId.get(picked)?.entry
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-3">
        <nav className="flex min-w-0 flex-wrap items-center gap-1 text-sm text-muted-foreground">
          <button type="button" onClick={onClose} className="hover:text-foreground">
            {t("steward.shell.page", { page: "translations" })}
          </button>
          <CaretRightIcon aria-hidden className="size-3 shrink-0" />
          <span>{t("steward.texts.group", { group: choice(group) })}</span>
          {area ? (
            <>
              <CaretRightIcon aria-hidden className="size-3 shrink-0" />
              <span>{area}</span>
            </>
          ) : null}
        </nav>
        <h1 className="font-heading text-3xl font-semibold tracking-tight text-balance">{cardName(card)}</h1>
      </header>
      {above}
      <div className={cn("grid grid-cols-1 items-start gap-4", layout && "lg:grid-cols-[minmax(0,1fr)_22rem]")}>
        {layout ? (
          <DiscordLayoutPreview
            layout={layout}
            language={language}
            languages={shared.languages}
            tiers={shared.tiers}
            draw={shared.draw}
            picked={picked}
            onPick={onPick}
          />
        ) : null}
        <section className="flex min-w-0 flex-col gap-3 rounded-lg border p-3">
          {entry ? (
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <h2 className="min-w-0 flex-1 truncate text-sm font-medium">{messageName(entry)}</h2>
              <Pills entry={entry} places={shared.places} />
            </div>
          ) : null}
          {editor}
          {layout ? (
            <section aria-labelledby={textsHeading} className="flex flex-col gap-1">
              <h3 id={textsHeading} className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
                {t("steward.texts.parts")}
                <span className="tabular-nums">{ids.length}</span>
              </h3>
              {ids.map((id) => {
                const part = shared.byId.get(id)?.entry
                if (!part) return null
                return (
                  <button
                    key={id}
                    type="button"
                    aria-pressed={id === picked}
                    onClick={() => onPick(id)}
                    className={cn(
                      "flex min-w-0 items-center gap-2 rounded-md border-l-2 border-transparent px-2 py-1.5 text-left hover:bg-accent/50",
                      id === picked && "border-primary bg-accent/50",
                    )}
                  >
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate text-sm">{messageName(part)}</span>
                      <MessagePreview
                        text={shared.draw(id, language)?.text ?? ""}
                        format={part.format}
                        className="text-xs text-muted-foreground"
                      />
                    </span>
                    {shared.fallenBack.has(id) ? <FallenBack /> : null}
                    {shared.draftIds.has(id) ? <DraftDot /> : null}
                  </button>
                )
              })}
            </section>
          ) : null}
        </section>
      </div>
      <PinnedRow>{save}</PinnedRow>
    </div>
  )
}

function Pills({ entry, places }: { entry: MessageEntry; places: Places }) {
  const pills = pillsOf(entry, places)
  if (pills.length === 0) return null
  return (
    <span aria-label={t("steward.texts.shown-in")} className="flex flex-wrap gap-1">
      {pills.map((pill) => (
        <Badge
          key={pill.kind === "place" ? pill.place : pill.surface}
          variant="outline"
          className="h-4 px-1.5 text-[0.625rem] text-muted-foreground"
        >
          {pill.kind === "place"
            ? t("steward.message-editor.place", { place: choice(pill.place) })
            : t("steward.texts.group", { group: choice(pill.surface) })}
        </Badge>
      ))}
    </span>
  )
}
