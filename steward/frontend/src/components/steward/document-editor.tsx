import { CaretRightIcon, PaperPlaneTiltIcon, PlusIcon, TrashIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type {
  SharedPartView,
  Document,
  DocumentItem,
  DocumentLine,
  DocumentLineStyle,
  DocumentPart,
  DocumentPartType,
  DocumentValue,
  DocumentView,
} from "@/lib/api"
import {
  addPart,
  changePart,
  holdsRequired,
  movePart,
  newLink,
  newPart,
  slotPart,
  slotsOf,
  unplacedSlots,
  versionsOf,
  withItem,
  withLine,
  type PartPath,
} from "@/lib/document-edit"
import { documentName } from "@/lib/documents"
import { ENGLISH, type Language } from "@/lib/message-text"
import { useCommandRun, useDocumentDrawing, useDocuments, useGameAction, useSaveDocument } from "@/lib/queries"
import { choice, t } from "@/lib/texts"
import { useDebounced } from "@/lib/use-debounced"
import { serviceTitle } from "@/lib/words"
import { cn } from "cn"
import { toast } from "sonner"
import { RequestOutcome } from "@/components/steward/game-actions"
import { PinnedRow } from "@/components/steward/settings-view"
import { Failure } from "@/components/steward/query-state"
import {
  AccentField,
  AddButton,
  CodeMark,
  IconButton,
  ImageField,
  Images,
  Labelled,
  LanguageToggle,
  Move,
  Required,
  TextField,
  WhenField,
  type Context,
} from "@/components/steward/document-fields"
import { SharedPartMenu } from "@/components/steward/shared-parts"
import { DocumentVersions } from "@/components/steward/document-versions"
import { DocumentPreview, PAUSE_MS } from "@/components/steward/message-editor/document-preview"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

/** The parts an admin adds; a block per language comes only with its slot. */
const ADDED: Exclude<DocumentPartType, "LANGUAGES" | "ITEM" | "LABEL">[] = [
  "CONTAINER",
  "TEXT",
  "SECTION",
  "SEPARATOR",
  "GALLERY",
  "FILE",
  "ROW",
]

const STYLES: DocumentLineStyle[] = ["TITLE", "HEADING"]

/**
 * A document's editor: every part of its structure folded to a line, its texts in the one language picked and what a
 * save would be refused for. A save is live at once.
 */
export function DocumentEditor({
  view,
  draft,
  onDraft,
  languages,
  language,
  onLanguage,
  writable,
}: {
  view: DocumentView
  draft: Document
  onDraft: (draft: Document | undefined) => void
  /** The network's languages, the default first. */
  languages: string[]
  /** The language edited and previewed. */
  language: Language
  onLanguage: (language: Language) => void
  writable: boolean
}) {
  const save = useSaveDocument()
  // The same debounced draft the preview draws, so both share one drawing.
  const drawing = useDocumentDrawing(view.declaration, useDebounced(draft, PAUSE_MS), language)
  const shown = view.written ?? view.packaged
  const changed = JSON.stringify(draft) !== JSON.stringify(shown)
  const problems = drawing.data?.problems ?? []
  const refused = problems.some((problem) => problem.error)
  const sharedParts = useDocuments().data?.sharedParts ?? []
  const context: Context = {
    view,
    language,
    writable,
    sharedParts,
    onPart: (path, change) => onDraft({ parts: changePart(draft.parts, path, change) }),
    onMove: (path, by) => onDraft({ parts: movePart(draft.parts, path, by) }),
  }
  const name = documentName(view.declaration)
  // A modal and a channel's name are no direct message; a game text goes to the admin's player.
  const sendable = view.game || view.declaration.shape === "MESSAGE" || view.declaration.shape === "PLAIN"
  const sendLabel = view.game ? t("steward.message-editor.show-in-game") : t("steward.message-editor.send-in-discord")
  const send = useSendDraft(view)

  // A restore is a save of the version's document, or of none for the packaged one.
  function submit(document: Document | null = draft) {
    save.mutate(
      { declaration: view.declaration, document },
      {
        onSuccess: () => {
          onDraft(undefined)
          toast.success(t("steward.document-editor.saved", { document: name }))
        },
      },
    )
  }

  return (
    <section aria-label={name} className="flex min-w-0 flex-col gap-3">
      {view.outdated ? <Outdated view={view} language={language} /> : null}
      {problems.length > 0 ? (
        <ul className="flex flex-col gap-1 text-xs">
          {problems.map((problem, index) => (
            <li key={index} className={problem.error ? "text-destructive" : "text-warning"}>
              {problem.text}
            </li>
          ))}
        </ul>
      ) : null}
      <Parts parts={draft.parts} path={[]} context={context} />
      <AddPart
        view={view}
        parts={draft.parts}
        sharedParts={sharedParts}
        disabled={!writable}
        onAdd={(part) => onDraft({ parts: addPart(draft.parts, [], part) })}
      />
      <DocumentVersions view={view} disabled={!writable || save.isPending} onRestore={submit} />
      {save.error ? <Failure error={save.error} /> : null}
      {send.error ? <Failure error={send.error} /> : null}
      {send.run ? <RequestOutcome run={send.run} /> : null}
      {/* The language and the save stay in reach while a long document scrolls. */}
      <PinnedRow>
        <div className="pointer-events-auto flex min-w-0 flex-1 flex-wrap items-center gap-1 rounded-md border bg-card/95 p-1 backdrop-blur md:gap-2 md:p-2">
          <LanguageToggle languages={languages} language={language} onLanguage={onLanguage} />
          {sendable ? (
            <Button
              type="button"
              variant="outline"
              size="icon"
              aria-label={sendLabel}
              title={sendLabel}
              disabled={refused || send.busy}
              onClick={() => send.send(draft, language)}
            >
              <PaperPlaneTiltIcon aria-hidden />
            </Button>
          ) : null}
          <span className="ml-auto flex items-center gap-1 md:gap-2">
            <Button
              type="button"
              variant="ghost"
              disabled={!changed || save.isPending}
              onClick={() => onDraft(undefined)}
            >
              {t("steward.document-editor.discard")}
            </Button>
            <Button
              type="button"
              disabled={!writable || !changed || refused || save.isPending}
              onClick={() => submit()}
            >
              {t("steward.document-editor.save")}
            </Button>
          </span>
        </div>
      </PinnedRow>
    </section>
  )
}

/** A Discord document's count of components against Discord's most, from the drawing its preview shares. */
export function ComponentCount({ view, draft, language }: { view: DocumentView; draft: Document; language: Language }) {
  const drawing = useDocumentDrawing(view.declaration, useDebounced(draft, PAUSE_MS), language)
  if (!drawing.data || view.game) return null
  return (
    <Badge
      variant="outline"
      className={cn("shrink-0 tabular-nums", drawing.data.components > drawing.data.most && "text-destructive")}
    >
      {t("steward.document-editor.components", {
        count: drawing.data.components,
        most: drawing.data.most,
      })}
    </Badge>
  )
}

/** Sends the draft as it stands to the admin alone: the bot's direct message, or their player in game. */
function useSendDraft(view: DocumentView) {
  const action = useGameAction()
  const [id, setId] = useState<string | null>(null)
  const run = useCommandRun(id)
  const settled = run.data !== undefined && run.data.status !== "PENDING" && run.data.status !== "RUNNING"
  return {
    busy: action.isPending || (id !== null && !settled && !run.error),
    error: action.error ?? run.error,
    run: id === null ? undefined : run.data,
    send: (document: Document, language: Language) => {
      setId(null)
      action.mutate(
        { path: "/api/document-preview", body: { declaration: view.declaration, document, language } },
        { onSuccess: (asked) => setId(asked.id) },
      )
    },
  }
}

/** The packaged document beside the admin's, when a release changed the packaged one after the admin saved. */
function Outdated({ view, language }: { view: DocumentView; language: Language }) {
  return (
    <>
      {view.adapted.length > 0 ? (
        <ul aria-label={t("steward.document-editor.adaptations")} className="flex flex-col gap-1 text-xs">
          {view.adapted.map(({ change, subject }) => (
            <li key={change + subject} className="flex flex-wrap gap-x-2 text-warning">
              <span>{t("steward.document-editor.adapted", { change: choice(change) })}</span>
              {/* A slot is named by its id; a set-aside names the check's reason. */}
              <span className={cn("min-w-0 break-words", change !== "SET_ASIDE" && "font-mono")}>{subject}</span>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {(
          [
            ["packaged", view.packaged],
            ["saved", view.written ?? view.packaged],
          ] as const
        ).map(([version, document]) => (
          <div key={version} className="flex min-w-0 flex-col gap-1">
            <h4 className="text-xs font-medium text-muted-foreground">
              {t("steward.document-editor.version", { version })}
            </h4>
            <DocumentPreview declaration={view.declaration} game={view.game} document={document} language={language} />
          </div>
        ))}
      </div>
    </>
  )
}

function Parts({ parts, path, context }: { parts: DocumentPart[]; path: PartPath; context: Context }) {
  return (
    <ol className="flex flex-col gap-2">
      {parts.map((part, index) => (
        <li key={index}>
          <PartEditor part={part} path={[...path, index]} last={index === parts.length - 1} context={context} />
        </li>
      ))}
    </ol>
  )
}

/**
 * One part folded to a line: on its left the buttons that move it and lock or remove it, then its kind and its first
 * words in the language picked. A click opens what it holds; only the document's first part starts open.
 */
function PartEditor({
  part,
  path,
  last,
  context,
}: {
  part: DocumentPart
  path: PartPath
  last: boolean
  context: Context
}) {
  const { view, writable, onPart } = context
  const set = (change: (part: DocumentPart) => DocumentPart | undefined) => onPart(path, change)
  const at = path[path.length - 1]
  const [open, setOpen] = useState(path.length === 1 && at === 0)
  // A separator holds nothing to open.
  const empty = part.type === "SEPARATOR"
  const words = firstWords(part, context.language)
  return (
    <div className={cn("min-w-0 rounded-md border", open && !empty && "bg-muted/40")}>
      <div className="flex min-w-0 items-center justify-between gap-1 p-1 md:justify-start">
        <span className="contents shrink-0 items-center md:flex">
          <Move disabled={!writable} first={at === 0} last={last} onMove={(by) => context.onMove(path, by)} />
          {holdsRequired(view.declaration, slotsOf(part)) ? (
            <Required />
          ) : (
            <IconButton
              label={t("steward.document-editor.remove")}
              disabled={!writable}
              onPress={() => set(() => undefined)}
            >
              <TrashIcon aria-hidden />
            </IconButton>
          )}
        </span>
        <button
          type="button"
          aria-expanded={empty ? undefined : open}
          disabled={empty}
          onClick={() => setOpen(!open)}
          className="flex min-w-0 items-center gap-1.5 rounded-sm p-1 text-left outline-none focus-visible:ring-2 focus-visible:ring-ring md:flex-1"
        >
          {empty ? null : (
            <CaretRightIcon
              aria-hidden
              className={cn(
                "size-3.5 shrink-0 transition-transform motion-reduce:transition-none",
                open && "rotate-90",
              )}
            />
          )}
          <span className="min-w-0 truncate text-xs font-medium">
            {t("steward.document-editor.part", { part: choice(part.type) })}
          </span>
          {part.slot ? <CodeMark slot={part.slot} /> : null}
          {open || !words ? null : (
            <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground">{words}</span>
          )}
        </button>
        <SharedPartMenu part={part} context={context} set={set} />
      </div>
      {open && (!empty || Object.keys(view.named).length > 0) ? (
        <div className="flex min-w-0 flex-col gap-2 border-t p-2">
          <WhenField
            view={view}
            of="PART"
            when={part.when}
            disabled={!writable}
            onWhen={(when) => set((it) => ({ ...it, when }))}
          />
          {empty ? null : <PartBody part={part} path={path} context={context} set={set} />}
        </div>
      ) : null}
    </div>
  )
}

/** The first written words of a part in `language`, or in English where it has none, for its folded line. */
function firstWords(part: DocumentPart, language: Language): string | undefined {
  const texts = [...(part.lines ?? []).map((line) => line.text), ...(part.items ?? []).map((item) => item.label)]
  for (const text of texts) {
    const words = versionsOf(text, language)[0] || versionsOf(text, ENGLISH)[0]
    if (words) return words
  }
  for (const inner of part.parts ?? []) {
    const words = firstWords(inner, language)
    if (words) return words
  }
  return undefined
}

function PartBody({
  part,
  path,
  context,
  set,
}: {
  part: DocumentPart
  path: PartPath
  context: Context
  set: (change: (part: DocumentPart) => DocumentPart | undefined) => void
}) {
  const { view, writable } = context
  switch (part.type) {
    case "CONTAINER":
      return (
        <>
          <AccentField
            accent={part.accent}
            disabled={!writable}
            onAccent={(accent) => set((it) => ({ ...it, accent }))}
          />
          <Parts parts={part.parts ?? []} path={path} context={context} />
          <AddPart
            view={view}
            parts={part.parts ?? []}
            sharedParts={context.sharedParts}
            inside
            disabled={!writable}
            onAdd={(added) => set((it) => ({ ...it, parts: [...(it.parts ?? []), added] }))}
          />
        </>
      )
    case "LANGUAGES":
      return <Parts parts={part.parts ?? []} path={path} context={context} />
    case "TEXT":
    case "ITEM":
    case "LABEL":
      return <TextBlock part={part} context={context} set={set} />
    case "SECTION":
      return (
        <>
          <Parts parts={part.parts ?? []} path={path} context={context} />
          {(part.parts?.length ?? 0) < 3 ? (
            <AddButton
              label={t("steward.document-editor.add-text")}
              disabled={!writable}
              onPress={() => set((it) => ({ ...it, parts: [...(it.parts ?? []), newPart("TEXT")] }))}
            />
          ) : null}
          <SectionBeside part={part} context={context} set={set} />
        </>
      )
    case "SEPARATOR":
      return null
    case "GALLERY":
      return <Images part={part} most={10} disabled={!writable} set={set} />
    case "FILE":
      return <Images part={part} most={1} disabled={!writable} set={set} />
    case "ROW":
      return (
        <>
          {(part.items ?? []).map((item, index) => (
            <ItemEditor
              key={index}
              item={item}
              context={context}
              onItem={(next) => set((it) => withItem(it, index, next))}
            />
          ))}
          {(part.items ?? []).every((item) => isButton(context, item)) && (part.items?.length ?? 0) < 5 ? (
            <AddButton
              label={t("steward.document-editor.add-link")}
              disabled={!writable}
              onPress={() => set((it) => ({ ...it, items: [...(it.items ?? []), newLink()] }))}
            />
          ) : null}
        </>
      )
    default: {
      const exhaustive: never = part.type
      throw new Error(`unreachable part type: ${String(exhaustive)}`)
    }
  }
}

function isButton(context: Context, item: DocumentItem): boolean {
  return (
    item.slot === undefined || context.view.declaration.slots.find((slot) => slot.id === item.slot)?.type === "BUTTON"
  )
}

function TextBlock({
  part,
  context,
  set,
}: {
  part: DocumentPart
  context: Context
  set: (change: (part: DocumentPart) => DocumentPart | undefined) => void
}) {
  const { view, writable } = context
  const menu = part.type === "ITEM" || part.type === "LABEL"
  // An item or label drawn once per entry also names the entry's values.
  const entry = menu ? view.declaration.slots.find((slot) => slot.id === part.slot)?.values : undefined
  return (
    <>
      {(part.lines ?? []).map((line, index) => (
        <LineEditor
          key={index}
          line={line}
          context={context}
          styled={!view.game}
          entry={entry}
          removable={(part.lines?.length ?? 0) > 1 && !(line.list && holdsRequired(view.declaration, [line.list]))}
          onLine={(next) => set((it) => withLine(it, index, next))}
        />
      ))}
      {part.type === "LABEL" ? null : (
        <AddButton
          label={t("steward.document-editor.add-line")}
          disabled={!writable}
          onPress={() => set((it) => ({ ...it, lines: [...(it.lines ?? []), { text: { [ENGLISH]: "" } }] }))}
        />
      )}
    </>
  )
}

function LineEditor({
  line,
  context,
  styled,
  entry,
  removable,
  onLine,
}: {
  line: DocumentLine
  context: Context
  /** Whether the line offers a title or heading, which only Discord draws. */
  styled: boolean
  entry: Record<string, DocumentValue> | undefined
  removable: boolean
  onLine: (line: DocumentLine | undefined) => void
}) {
  const { view, writable } = context
  const listed = line.list ? view.declaration.slots.find((slot) => slot.id === line.list)?.values : undefined
  const item = entry || listed ? { ...entry, ...listed } : undefined
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <div className="flex min-w-0 flex-wrap items-center gap-1">
        {line.list ? (
          <CodeMark slot={line.list} />
        ) : !styled ? null : (
          <Select
            value={line.style ?? "PLAIN"}
            disabled={!writable}
            onValueChange={(next) => onLine({ ...line, style: STYLES.find((style) => style === next) })}
          >
            <SelectTrigger
              size="sm"
              aria-label={t("steward.document-editor.style", { style: choice(line.style ?? "PLAIN") })}
              className="w-24"
            >
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(["PLAIN", ...STYLES] as const).map((style) => (
                <SelectItem key={style} value={style}>
                  {t("steward.document-editor.style", { style: choice(style) })}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
        <span className="flex-1" />
        <WhenField
          view={view}
          of="LINE"
          when={line.when}
          disabled={!writable}
          onWhen={(when) => onLine({ ...line, when })}
        />
        {removable ? (
          <IconButton
            label={t("steward.document-editor.remove")}
            disabled={!writable}
            onPress={() => onLine(undefined)}
          >
            <TrashIcon aria-hidden />
          </IconButton>
        ) : null}
      </div>
      <TextField
        text={line.text}
        context={context}
        values={item}
        onText={(text) => onLine({ ...line, text })}
        multiline
      />
    </div>
  )
}

function ItemEditor({
  item,
  context,
  onItem,
}: {
  item: DocumentItem
  context: Context
  onItem: (item: DocumentItem | undefined) => void
}) {
  const { view, writable } = context
  const slot = item.slot ? view.declaration.slots.find((each) => each.id === item.slot) : undefined
  const locked = slot?.required === true
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <div className="flex min-w-0 flex-wrap items-center gap-1">
        {item.slot ? (
          <CodeMark slot={item.slot} />
        ) : (
          <span className="text-xs">{t("steward.document-editor.link")}</span>
        )}
        <span className="flex-1" />
        <WhenField view={view} when={item.when} disabled={!writable} onWhen={(when) => onItem({ ...item, when })} />
        {locked ? (
          <Required />
        ) : (
          <IconButton
            label={t("steward.document-editor.remove")}
            disabled={!writable}
            onPress={() => onItem(undefined)}
          >
            <TrashIcon aria-hidden />
          </IconButton>
        )}
      </div>
      {item.slot === undefined ? (
        <Input
          aria-label={t("steward.document-editor.link")}
          value={item.link ?? ""}
          disabled={!writable}
          inputMode="url"
          onChange={(event) => onItem({ ...item, link: event.target.value })}
        />
      ) : null}
      {item.label !== undefined || item.slot === undefined || slot?.type === "BUTTON" || slot?.type === "INPUT" ? (
        <Labelled label={t("steward.document-editor.label")}>
          <TextField text={item.label} context={context} onText={(label) => onItem({ ...item, label })} />
        </Labelled>
      ) : null}
      {item.placeholder !== undefined || slot?.type === "SELECT" ? (
        <Labelled label={t("steward.document-editor.placeholder")}>
          <TextField
            text={item.placeholder}
            context={context}
            onText={(placeholder) => onItem({ ...item, placeholder })}
          />
        </Labelled>
      ) : null}
      {item.option !== undefined ? (
        <Labelled label={t("steward.document-editor.option")}>
          <TextField
            text={item.option}
            context={context}
            values={slot?.values}
            onText={(option) => onItem({ ...item, option })}
          />
        </Labelled>
      ) : null}
    </div>
  )
}

/** A section's button or thumbnail. A slot's button stays a button. */
function SectionBeside({
  part,
  context,
  set,
}: {
  part: DocumentPart
  context: Context
  set: (change: (part: DocumentPart) => DocumentPart | undefined) => void
}) {
  const { writable } = context
  const beside = part.button ? "BUTTON" : "THUMBNAIL"
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <Select
        value={beside}
        disabled={!writable || part.button?.slot !== undefined}
        onValueChange={(next) =>
          set((it) =>
            next === "BUTTON"
              ? { ...it, button: newLink(), thumbnail: undefined }
              : { ...it, button: undefined, thumbnail: "" },
          )
        }
      >
        <SelectTrigger
          size="sm"
          aria-label={t("steward.document-editor.beside", { part: choice(beside) })}
          className="w-36"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {(["BUTTON", "THUMBNAIL"] as const).map((each) => (
            <SelectItem key={each} value={each}>
              {t("steward.document-editor.beside", { part: choice(each) })}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {part.button ? (
        <ItemEditor
          item={part.button}
          context={context}
          onItem={(button) => set((it) => ({ ...it, button, thumbnail: button ? undefined : "" }))}
        />
      ) : (
        <ImageField
          address={part.thumbnail ?? ""}
          disabled={!writable}
          onAddress={(thumbnail) => set((it) => ({ ...it, thumbnail }))}
        />
      )}
    </div>
  )
}

/** The menu that adds a part, a copy of a shared part, or a slot the code fills that no part places yet. */
function AddPart({
  view,
  parts,
  sharedParts,
  inside,
  disabled,
  onAdd,
}: {
  view: DocumentView
  parts: DocumentPart[]
  sharedParts: readonly SharedPartView[]
  inside?: boolean
  disabled: boolean
  onAdd: (part: DocumentPart) => void
}) {
  const slots = inside ? [] : unplacedSlots(view.declaration, [])
  const missing = unplacedSlots(view.declaration, parts).filter((slot) => slots.some((each) => each.id === slot.id))
  // A menu holds its title, then only the items and labels the code places; any other game text holds text blocks.
  const kinds =
    view.declaration.shape === "MENU"
      ? []
      : view.game
        ? (["TEXT"] as const)
        : inside
          ? ADDED.filter((type) => type !== "CONTAINER")
          : ADDED
  const insertable = sharedParts.filter((shared) => (kinds as readonly string[]).includes(shared.part.type))
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild disabled={disabled}>
        <Button type="button" variant="outline" size="sm" className="self-start">
          <PlusIcon aria-hidden />
          {t("steward.document-editor.add")}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-auto">
        {kinds.map((type) => (
          <DropdownMenuItem key={type} onSelect={() => onAdd(newPart(type))}>
            {t("steward.document-editor.part", { part: choice(type) })}
          </DropdownMenuItem>
        ))}
        {insertable.length > 0 ? <DropdownMenuSeparator /> : null}
        {insertable.map((shared) => (
          <DropdownMenuItem key={shared.name} onSelect={() => onAdd({ ...shared.part, shared: shared.name })}>
            {t("steward.document-editor.insert-shared-part", { name: shared.name })}
          </DropdownMenuItem>
        ))}
        {missing.map((slot) => (
          <DropdownMenuItem key={slot.id} onSelect={() => onAdd(slotPart(slot))} className="font-mono text-xs">
            {t("steward.document-editor.slot", { slot: slot.id })}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** Whether an admin changed the document, and whether a release changed the packaged one since. */
export function DocumentMarks({ view }: { view: DocumentView }) {
  return (
    <>
      {/* Several servers ship a game document, each one its own or all one shared. */}
      {view.game ? (
        <span className="flex flex-wrap gap-1">
          {view.services.map((service) => (
            <Badge key={service} variant="outline" className="h-4 px-1.5 text-[0.625rem] text-muted-foreground">
              {serviceTitle(service)}
            </Badge>
          ))}
        </span>
      ) : null}
      {view.written ? (
        <Badge variant="outline" className="shrink-0">
          {t("steward.document-editor.changed")}
        </Badge>
      ) : null}
      {view.written && view.outdated ? (
        <Badge variant="outline" className="shrink-0 text-warning">
          {t("steward.document-editor.outdated")}
        </Badge>
      ) : null}
    </>
  )
}
