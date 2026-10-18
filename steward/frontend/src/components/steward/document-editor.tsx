import { ArrowDownIcon, ArrowUpIcon, LockSimpleIcon, PlusIcon, TrashIcon, XIcon } from "@phosphor-icons/react"
import { useState } from "react"

import type {
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
  withItem,
  withLine,
  type PartPath,
} from "@/lib/document-edit"
import { documentName } from "@/lib/documents"
import { languageName } from "@/lib/language-names"
import { ENGLISH, type Language } from "@/lib/message-text"
import { useDocumentDrawing, useSaveDocument } from "@/lib/queries"
import { choice, t } from "@/lib/texts"
import { useDebounced } from "@/lib/use-debounced"
import { cn } from "cn"
import { toast } from "sonner"
import { Failure } from "@/components/steward/query-state"
import {
  AccentField,
  AddButton,
  CodeMark,
  IconButton,
  ImageField,
  Labelled,
  TextField,
  WhenField,
  type Context,
} from "@/components/steward/document-fields"
import { DocumentPreview, PAUSE_MS } from "@/components/steward/message-editor/document-preview"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
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
 * A document's editor: every part of its structure with its texts in English and one more language, a Discord
 * document's count of components and what a save would be refused for. A save is live at once.
 */
export function DocumentEditor({
  view,
  draft,
  onDraft,
  languages,
  beside,
  onBeside,
  writable,
  onClose,
}: {
  view: DocumentView
  draft: Document
  onDraft: (draft: Document | undefined) => void
  /** The network's languages, the default first. */
  languages: string[]
  beside: Language | undefined
  onBeside: (language: Language | undefined) => void
  writable: boolean
  onClose: () => void
}) {
  const save = useSaveDocument()
  // The same debounced draft the preview draws, so both share one drawing.
  const drawing = useDocumentDrawing(view.declaration, useDebounced(draft, PAUSE_MS), beside ?? ENGLISH)
  const shown = view.written ?? view.packaged
  const changed = JSON.stringify(draft) !== JSON.stringify(shown)
  const problems = drawing.data?.problems ?? []
  const refused = problems.some((problem) => problem.error)
  const others = languages.filter((language) => language !== ENGLISH)
  const context: Context = {
    view,
    beside,
    writable,
    onPart: (path, change) => onDraft({ parts: changePart(draft.parts, path, change) }),
    onMove: (path, by) => onDraft({ parts: movePart(draft.parts, path, by) }),
  }
  const name = documentName(view.declaration)

  function submit() {
    save.mutate(
      { declaration: view.declaration, document: draft },
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
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <h3 className="min-w-0 flex-1 truncate text-sm font-medium">{name}</h3>
        <DocumentMarks view={view} />
        {drawing.data && view.declaration.shape !== "MENU" ? (
          <Badge
            variant="outline"
            className={cn("shrink-0 tabular-nums", drawing.data.components > drawing.data.most && "text-destructive")}
          >
            {t("steward.document-editor.components", { count: drawing.data.components, most: drawing.data.most })}
          </Badge>
        ) : null}
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={t("steward.document-editor.close")}
          onClick={onClose}
        >
          <XIcon aria-hidden />
        </Button>
      </div>
      {others.length > 0 ? (
        <Select
          value={beside ?? ENGLISH}
          onValueChange={(language) => onBeside(language === ENGLISH ? undefined : language)}
        >
          <SelectTrigger aria-label={t("steward.document-editor.language")} className="w-full sm:w-52">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ENGLISH}>{languageName(ENGLISH)}</SelectItem>
            {others.map((language) => (
              <SelectItem key={language} value={language}>
                {languageName(language)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      ) : null}
      {view.outdated ? <Outdated view={view} language={beside ?? ENGLISH} /> : null}
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
        disabled={!writable}
        onAdd={(part) => onDraft({ parts: addPart(draft.parts, [], part) })}
      />
      {save.error ? <Failure error={save.error} /> : null}
      <div className="flex flex-wrap justify-end gap-2">
        <Button type="button" variant="ghost" disabled={!changed || save.isPending} onClick={() => onDraft(undefined)}>
          {t("steward.document-editor.discard")}
        </Button>
        <Button type="button" disabled={!writable || !changed || refused || save.isPending} onClick={submit}>
          {t("steward.document-editor.save")}
        </Button>
      </div>
    </section>
  )
}

/** The packaged document beside the admin's, when a release changed the packaged one after the admin saved. */
function Outdated({ view, language }: { view: DocumentView; language: Language }) {
  return (
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
          <DocumentPreview declaration={view.declaration} document={document} language={language} />
        </div>
      ))}
    </div>
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

/** One part: its kind, its condition and the buttons that move or remove it, then what it holds. */
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
  const locked = holdsRequired(view.declaration, slotsOf(part))
  const at = path[path.length - 1]
  return (
    <div className="flex min-w-0 flex-col gap-2 border-l-2 pl-3">
      <div className="flex min-w-0 flex-wrap items-center gap-1">
        <span className="min-w-0 flex-1 truncate text-xs font-medium">
          {t("steward.document-editor.part", { part: choice(part.type) })}
        </span>
        {part.slot ? <CodeMark slot={part.slot} /> : null}
        <Move disabled={!writable} first={at === 0} last={last} onMove={(by) => context.onMove(path, by)} />
        {locked ? (
          <LockSimpleIcon
            aria-label={t("steward.document-editor.required")}
            className="size-4 shrink-0 text-muted-foreground"
          />
        ) : (
          <IconButton
            label={t("steward.document-editor.remove")}
            disabled={!writable}
            onPress={() => set(() => undefined)}
          >
            <TrashIcon aria-hidden />
          </IconButton>
        )}
      </div>
      <WhenField view={view} when={part.when} disabled={!writable} onWhen={(when) => set((it) => ({ ...it, when }))} />
      <PartBody part={part} path={path} context={context} set={set} />
    </div>
  )
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
          styled={!menu}
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
  /** Whether the line offers a title or heading, which a menu's item or label does not draw. */
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
        <WhenField view={view} when={line.when} disabled={!writable} onWhen={(when) => onLine({ ...line, when })} />
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
          <LockSimpleIcon
            aria-label={t("steward.document-editor.required")}
            className="size-4 shrink-0 text-muted-foreground"
          />
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

function Images({
  part,
  most,
  disabled,
  set,
}: {
  part: DocumentPart
  most: number
  disabled: boolean
  set: (change: (part: DocumentPart) => DocumentPart | undefined) => void
}) {
  const media = part.media ?? []
  return (
    <>
      {media.map((address, index) => (
        <div key={index} className="flex min-w-0 items-start gap-1">
          <ImageField
            address={address}
            disabled={disabled}
            onAddress={(next) => set((it) => ({ ...it, media: media.map((each, at) => (at === index ? next : each)) }))}
          />
          {media.length > 1 ? (
            <IconButton
              label={t("steward.document-editor.remove")}
              disabled={disabled}
              onPress={() => set((it) => ({ ...it, media: media.filter((_, at) => at !== index) }))}
            >
              <TrashIcon aria-hidden />
            </IconButton>
          ) : null}
        </div>
      ))}
      {media.length < most ? (
        <AddButton
          label={t("steward.document-editor.add-image")}
          disabled={disabled}
          onPress={() => set((it) => ({ ...it, media: [...media, ""] }))}
        />
      ) : null}
    </>
  )
}

/** The menu that adds a part, or a slot the code fills that no part places yet. */
function AddPart({
  view,
  parts,
  inside,
  disabled,
  onAdd,
}: {
  view: DocumentView
  parts: DocumentPart[]
  inside?: boolean
  disabled: boolean
  onAdd: (part: DocumentPart) => void
}) {
  const slots = inside ? [] : unplacedSlots(view.declaration, [])
  const missing = unplacedSlots(view.declaration, parts).filter((slot) => slots.some((each) => each.id === slot.id))
  // A menu holds its title, then only the items and labels the code places.
  const kinds = view.declaration.shape === "MENU" ? [] : inside ? ADDED.filter((type) => type !== "CONTAINER") : ADDED
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
        {missing.map((slot) => (
          <DropdownMenuItem key={slot.id} onSelect={() => onAdd(slotPart(slot))} className="font-mono text-xs">
            {t("steward.document-editor.slot", { slot: slot.id })}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function Move({
  disabled,
  first,
  last,
  onMove,
}: {
  disabled: boolean
  first: boolean
  last: boolean
  onMove: (by: -1 | 1) => void
}) {
  return (
    <>
      <IconButton label={t("steward.document-editor.move-up")} disabled={disabled || first} onPress={() => onMove(-1)}>
        <ArrowUpIcon aria-hidden />
      </IconButton>
      <IconButton label={t("steward.document-editor.move-down")} disabled={disabled || last} onPress={() => onMove(1)}>
        <ArrowDownIcon aria-hidden />
      </IconButton>
    </>
  )
}

/** Keeps the draft of each opened document while the page lives. */
export function useDocumentDrafts() {
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

/** Whether an admin changed the document, and whether a release changed the packaged one since. */
export function DocumentMarks({ view }: { view: DocumentView }) {
  if (!view.written) return null
  return (
    <>
      <Badge variant="outline" className="shrink-0">
        {t("steward.document-editor.changed")}
      </Badge>
      {view.outdated ? (
        <Badge variant="outline" className="shrink-0 text-warning">
          {t("steward.document-editor.outdated")}
        </Badge>
      ) : null}
    </>
  )
}
