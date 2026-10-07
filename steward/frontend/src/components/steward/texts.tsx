import { CaretRightIcon, WarningIcon } from "@phosphor-icons/react"
import { useEffect, useMemo, useState } from "react"
import { cn } from "cn"

import type { MessageChanges, MessageEntry, MessageFallback, MessageText, MessageTexts, Warning } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import {
  ENGLISH,
  isLanguage,
  languagesByBundle,
  overrideOf,
  packagedOf,
  shownOf,
  type Language,
} from "@/lib/message-text"
import { useMessageFallbacks, useSaveMessageTexts } from "@/lib/queries"
import { messageLeafMatches, messageName, textId, textsTree } from "@/lib/settings-tree"
import { pillsOf, type Places } from "@/lib/text-places"
import { Failure } from "@/components/steward/query-state"
import { SaveDraft } from "@/components/steward/group-draft"
import { Badge } from "@/components/ui/badge"
import { MessagePreview } from "@/components/steward/message-preview"
import {
  MessageEditor,
  type MessageDraft,
  type MessageEditorProps,
} from "@/components/steward/message-editor/message-editor"
import { DraftDot, type Target, TreeView } from "@/components/steward/settings-view"
import { choice, message, t } from "@/lib/texts"

/** The Texts page's form: every text in its group and topic, where it appears, its editor, and the one save. */

/** The one draft of the Texts page, keyed by each text's `<bundle>/<key>`. */
export const TEXTS_FILE = "texts"

/** No palette of a service's own, one object so the editor's memo of its tones holds. */
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

/** The draft as a save sends it, every key under its bundle. */
function changesOf(draft: Record<string, MessageDraft>, byId: Map<string, MessageText>): MessageChanges["changes"] {
  const changes: MessageChanges["changes"] = {}
  for (const [id, changed] of Object.entries(draft)) {
    const entry = byId.get(id)?.entry
    if (!entry) continue
    changes[entry.bundle] = { ...changes[entry.bundle], [entry.key]: changed }
  }
  return changes
}

export function TextsForm({
  texts,
  service,
  target,
}: {
  texts: MessageTexts
  /** The service the page is filtered to: only its texts, in its palette. */
  service: string | undefined
  target: Target | null
}) {
  const draft = useDraft<MessageDraft>(TEXTS_FILE, isMessageDraftRecord)
  const save = useSaveMessageTexts()
  const takeOver = useSaveMessageTexts()
  const fallbacks = useMessageFallbacks()
  const [warnings, setWarnings] = useState<Warning[]>([])
  const listed = useMemo(
    () => (service === undefined ? texts.texts : texts.texts.filter((text) => text.services.includes(service))),
    [texts.texts, service],
  )
  const nodes = useMemo(() => textsTree(listed, texts.places), [listed, texts.places])
  const byId = useMemo(() => new Map(texts.texts.map((text) => [textId(text.entry), text])), [texts.texts])
  const languages = useMemo(
    () =>
      languagesByBundle(
        texts.texts.map((text) => text.entry),
        texts.languages,
      ),
    [texts.texts, texts.languages],
  )
  /** The filtered service's palette, else none, which leaves the network's own. */
  const colours = service === undefined ? undefined : texts.colours[service]
  const fallenBack = useMemo(() => {
    const found = new Map<string, MessageFallback[]>()
    for (const fallback of fallbacks.data ?? []) {
      const id = `${fallback.bundle}/${fallback.key}`
      found.set(id, [...(found.get(id) ?? []), fallback])
    }
    return found
  }, [fallbacks.data])
  // One key open at a time: opening another closes this one, and its draft stays where it is.
  const [openId, setOpenId] = useState<string | null>(null)

  const draftIds = useMemo(() => new Set(Object.keys(draft)), [draft])
  const count = Object.values(draft).reduce((sum, changed) => sum + Object.keys(changed).length, 0)

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

  /** Saves a fallen-back override as it stands, which writes it over the jar's text of now; its draft goes. */
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
  return (
    <TreeView<MessageText>
      file={TEXTS_FILE}
      nodes={nodes}
      draftIds={draftIds}
      matches={(text, query) => messageLeafMatches(text.entry, query)}
      notice={texts.writable ? null : t("steward.settings.read-only")}
      target={target}
      searchLabel={t("steward.texts.search")}
      empty={t("steward.texts.none")}
      above={
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
      }
      collapsed
      list
      renderLeaf={(leaf, highlight) => {
        const { entry, path, previews } = leaf.value
        return (
          <TextRow
            entry={entry}
            path={path}
            service={service}
            languages={languages.get(entry.bundle) ?? [ENGLISH]}
            colours={colours}
            places={texts.places}
            open={openId === leaf.id}
            onOpen={(open) => setOpenId(open ? leaf.id : null)}
            writable={texts.writable}
            draft={draft[leaf.id]}
            highlight={highlight}
            onChange={(language, value) => set(leaf.id, language, value)}
            fallbacks={fallenBack.get(leaf.id) ?? []}
            onTakeOver={takeOverOf(entry)}
            takingOver={takeOver.isPending}
            previews={previews}
          />
        )
      }}
      save={
        <SaveDraft
          count={count}
          writable={texts.writable}
          pending={save.isPending}
          onSave={submit}
          onDiscard={() => clearDraft(TEXTS_FILE)}
        />
      }
    />
  )
}

/** Where a text appears: one pill per place, or one for a whole surface it fills. */
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

/** One key: its name, its text on one line and where it appears, and once opened its editor right below it. */
function TextRow({
  open,
  onOpen,
  colours,
  ...editor
}: Omit<MessageEditorProps, "colours"> & {
  /** The filtered service's palette, `undefined` for the network's. */
  colours: Record<string, string> | undefined
  open: boolean
  onOpen: (open: boolean) => void
}) {
  const { entry, draft, highlight, fallbacks, places } = editor
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
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="truncate text-sm">{name}</span>
          <MessagePreview
            text={text}
            format={entry.format}
            colours={colours}
            className="text-xs text-muted-foreground"
          />
          <Pills entry={entry} places={places} />
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
          <MessageEditor {...editor} colours={colours ?? NETWORK_COLOURS} />
        </div>
      ) : null}
    </div>
  )
}
