import { Hint } from "@/components/steward/status"
import { PuzzlePieceIcon } from "@phosphor-icons/react"
import { use, useId, useState } from "react"

import type { SharedPartView, DocumentPart } from "@/lib/api"
import { documentId, languagesOf, sharedPartId } from "@/lib/documents"
import type { Language } from "@/lib/message-text"
import { useDocuments, useSharedPartChanges, useSaveSharedPart } from "@/lib/queries"
import { addPart, changePart, lastPath, movePart, type PartPath } from "@/lib/document-edit"
import { choice, t } from "@/lib/texts"
import { toast } from "sonner"
import { Failure, QueryState } from "@/components/steward/query-state"
import { DocumentPreview } from "@/components/steward/message-editor/document-preview"
import { OpenBlock } from "@/components/steward/message-editor/preview"
import type { Context } from "@/components/steward/document-fields"
import { PartBody } from "@/components/steward/document-editor"
import { PinnedRow } from "@/components/steward/settings-view"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

/** A shared part's name, as the server accepts it. */
const NAME = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/

/**
 * The shared part drawn in a part's place, and the menu that detaches it, or that saves a part as a shared part, which
 * the part then names.
 */
export function SharedPartMenu({
  part,
  context,
  set,
}: {
  part: DocumentPart
  context: Context
  set: (change: (part: DocumentPart) => DocumentPart) => void
}) {
  const [open, setOpen] = useState(false)
  const saved = context.sharedParts.find((each) => each.name === part.shared)
  const opener = use(OpenBlock)
  const openShared = part.shared ? opener(sharedPartId(part.shared)) : undefined
  const badge = part.shared ? (
    <>
      <PuzzlePieceIcon aria-hidden />
      {/* The icon says shared part, so the badge shows the name alone. */}
      <span className="sr-only">{t("steward.document-editor.shared-part", { name: part.shared })}</span>
      <span aria-hidden className="truncate">
        {part.shared}
      </span>
    </>
  ) : null
  return (
    <>
      {part.shared ? (
        // On a phone the header is full, so the badge takes a line of its own under it.
        <span className="flex min-w-0 max-md:order-last max-md:basis-full max-md:px-1 max-md:pb-1">
          <Hint tip={t("steward.document-editor.shared-part", { name: part.shared })}>
            <Badge asChild={openShared !== undefined} variant="outline" className="min-w-0 shrink">
              {openShared ? (
                <button type="button" onClick={openShared} className="cursor-pointer hover:bg-muted">
                  {badge}
                </button>
              ) : (
                badge
              )}
            </Badge>
          </Hint>
        </span>
      ) : null}
      <DropdownMenu>
        <Hint tip={t("steward.document-editor.shared-part-menu")}>
          <DropdownMenuTrigger asChild>
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("steward.document-editor.shared-part-menu")}
            >
              <PuzzlePieceIcon aria-hidden />
            </Button>
          </DropdownMenuTrigger>
        </Hint>
        <DropdownMenuContent className="w-auto">
          {part.shared ? (
            <DropdownMenuItem
              disabled={!saved}
              onSelect={() => set((it) => (saved ? { ...saved.part, when: it.when ?? saved.part.when } : it))}
            >
              {t("steward.document-editor.detach-shared-part")}
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem onSelect={() => setOpen(true)}>
              {t("steward.document-editor.save-shared-part")}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {open ? (
        <SharedPartDialog
          part={part}
          sharedParts={context.sharedParts}
          language={context.language}
          onClose={() => setOpen(false)}
          onShared={(shared) => set((it) => (shared ? { type: it.type, when: it.when, shared } : it))}
        />
      ) : null}
    </>
  )
}

/**
 * Saves a part as a named shared part, or deletes the one saved under the name. Before the save it shows each saved
 * document that names the shared part, before and after, as the renderers draw them.
 */
export function SharedPartDialog({
  part,
  named,
  sharedParts,
  language,
  onClose,
  onShared,
}: {
  part: DocumentPart
  /** The shared part's name where the part is that shared part, which asks for no name. */
  named?: string
  sharedParts: readonly SharedPartView[]
  language: Language
  onClose: () => void
  /** Makes the part name the shared part saved; `undefined` after the shared part was deleted. */
  onShared: (shared: string | undefined) => void
}) {
  const [typed, setName] = useState("")
  const name = named ?? typed
  const field = useId()
  const valid = name.length <= 64 && NAME.test(name)
  const changes = useSharedPartChanges(name, part, valid)
  const save = useSaveSharedPart()
  const exists = sharedParts.some((shared) => shared.name === name)
  const refused = changes.data?.some((change) => change.problems.length > 0) ?? false

  function submit(saved: DocumentPart | null) {
    save.mutate(
      { name, part: saved },
      {
        onSuccess: () => {
          onShared(saved ? name : undefined)
          toast.success(t("steward.document-editor.shared-part-saved", { name, deleted: saved === null }))
          onClose()
        },
      },
    )
  }

  return (
    <ResponsiveDialog open onOpenChange={(open) => (open ? null : onClose())}>
      <ResponsiveDialogContent className="sm:max-w-3xl" aria-describedby={undefined}>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>
            {named
              ? t("steward.document-editor.save-shared-part-named", { name: named })
              : t("steward.document-editor.save-shared-part")}
          </ResponsiveDialogTitle>
        </ResponsiveDialogHeader>
        <div className="flex min-w-0 flex-col gap-3 overflow-y-auto">
          {named ? null : (
            <div className="flex min-w-0 flex-col gap-1 text-xs">
              <label htmlFor={field} className="font-medium">
                {t("steward.document-editor.shared-part-name")}
              </label>
              <Input
                id={field}
                value={name}
                autoFocus
                autoComplete="off"
                spellCheck={false}
                aria-invalid={name !== "" && !valid}
                aria-describedby={`${field}-rule`}
                onChange={(event) => setName(event.target.value)}
              />
              <span
                id={`${field}-rule`}
                className={name !== "" && !valid ? "text-destructive" : "text-muted-foreground"}
              >
                {t("steward.document-editor.shared-part-name-rule")}
              </span>
              {exists ? (
                <span className="text-warning">{t("steward.document-editor.shared-part-exists", { name })}</span>
              ) : null}
            </div>
          )}
          {!valid ? null : (
            <QueryState query={changes} rows={2}>
              {(list) =>
                list.length === 0 ? (
                  <p className="text-xs text-muted-foreground">{t("steward.document-editor.shared-part-unused")}</p>
                ) : (
                  <ul className="flex min-w-0 flex-col gap-4">
                    {list.map((change) => (
                      <li
                        key={`${change.declaration.namespace}/${change.declaration.name}`}
                        className="flex min-w-0 flex-col gap-2"
                      >
                        <h4 className="truncate text-sm font-medium">{change.declaration.title}</h4>
                        {change.problems.map((problem, index) => (
                          <p key={index} className="text-xs text-destructive">
                            {problem}
                          </p>
                        ))}
                        <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
                          {(
                            [
                              ["before", change.before],
                              ["after", change.after],
                            ] as const
                          ).map(([state, document]) => (
                            <div key={state} className="flex min-w-0 flex-col gap-1">
                              <span className="text-xs text-muted-foreground">
                                {t("steward.document-editor.before-after", { state })}
                              </span>
                              <DocumentPreview
                                declaration={change.declaration}
                                game={change.game}
                                document={document}
                                language={language}
                              />
                            </div>
                          ))}
                        </div>
                      </li>
                    ))}
                  </ul>
                )
              }
            </QueryState>
          )}
          {save.error ? <Failure error={save.error} write /> : null}
        </div>
        <ResponsiveDialogFooter>
          {exists ? (
            <Button
              type="button"
              variant="destructive"
              className="sm:mr-auto"
              disabled={save.isPending}
              onClick={() => submit(null)}
            >
              {t("steward.document-editor.delete-shared-part")}
            </Button>
          ) : null}
          <Button type="button" variant="outline" onClick={onClose}>
            {t("steward.form.cancel")}
          </Button>
          <Button
            type="button"
            disabled={!valid || !changes.data || refused || save.isPending}
            onClick={() => submit(part)}
          >
            {t("steward.document-editor.save")}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/**
 * A shared part's editor, apart from every document. A save shows each document that names the part before and after.
 * The values it may name are those that every such document has.
 */
export function SharedPartEditor({
  shared,
  draft,
  onDraft,
  language,
}: {
  shared: SharedPartView
  draft: DocumentPart
  onDraft: (draft: DocumentPart | undefined) => void
  language: Language
}) {
  const list = useDocuments().data
  const [saving, setSaving] = useState(false)
  const [added, setAdded] = useState<PartPath>()
  const naming = (list?.documents ?? []).filter((view) => shared.documents.includes(documentId(view.declaration)))
  // shortcut: a part no document names borrows the first document's shape, which only decides game or Discord text.
  const base = naming[0] ?? list?.documents[0]
  if (!list || !base) return null
  const named = Object.fromEntries(
    Object.entries(base.named).filter(([name]) => naming.every((view) => name in view.named)),
  )
  const changed = JSON.stringify(draft) !== JSON.stringify(shared.part)
  const set = (change: (part: DocumentPart) => DocumentPart | undefined) => onDraft(change(draft) ?? draft)
  const context: Context = {
    view: { ...base, named },
    language,
    sharedParts: list.sharedParts,
    problems: [],
    onPart: (path, change) => onDraft(changePart([draft], path, change)[0] ?? draft),
    onMove: (path, by) => onDraft(movePart([draft], path, by)[0] ?? draft),
    languages: languagesOf({ parts: [draft] }, list.sharedParts),
    onAdd: (path, part) => {
      const parts = addPart([draft], path, part)
      setAdded(lastPath(parts, path))
      onDraft(parts[0] ?? draft)
    },
    added,
  }
  return (
    <section aria-label={shared.name} className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-col gap-2 rounded-md border bg-muted/40 p-2">
        <span className="text-xs font-medium">{t("steward.document-editor.part", { part: choice(draft.type) })}</span>
        <PartBody part={draft} path={[0]} context={context} set={set} />
      </div>
      <PinnedRow>
        <div className="pointer-events-auto flex min-w-0 flex-1 items-center justify-end gap-1 rounded-md border bg-card/95 p-1 backdrop-blur md:gap-2 md:p-2">
          <Button type="button" variant="ghost" disabled={!changed} onClick={() => onDraft(undefined)}>
            {t("steward.document-editor.discard")}
          </Button>
          <Button type="button" disabled={!changed} onClick={() => setSaving(true)}>
            {t("steward.document-editor.save")}
          </Button>
        </div>
      </PinnedRow>
      {saving ? (
        <SharedPartDialog
          part={draft}
          named={shared.name}
          sharedParts={list.sharedParts}
          language={language}
          onClose={() => setSaving(false)}
          onShared={() => onDraft(undefined)}
        />
      ) : null}
    </section>
  )
}
