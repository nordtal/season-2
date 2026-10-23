import { PuzzlePieceIcon } from "@phosphor-icons/react"
import { useId, useRef, useState } from "react"

import type { SharedPartView, DocumentPart } from "@/lib/api"
import { documentName } from "@/lib/documents"
import type { Language } from "@/lib/message-text"
import { useSharedPartChanges, useSaveSharedPart } from "@/lib/queries"
import { t } from "@/lib/texts"
import { toast } from "sonner"
import { Failure, QueryState } from "@/components/steward/query-state"
import { DocumentPreview } from "@/components/steward/message-editor/document-preview"
import type { Context } from "@/components/steward/document-fields"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ui/responsive-dialog"

/** A shared part's name, as the server accepts it. */
const NAME = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/

/** Returns `part` without any shared part's name, as a shared part is stored: it holds no other shared part. */
export function unmarked(part: DocumentPart): DocumentPart {
  const { shared: _, ...rest } = part
  return rest.parts ? { ...rest, parts: rest.parts.map(unmarked) } : rest
}

/**
 * The shared part a part is a copy of, with a warning where the copy differs, and the menu that saves, updates or
 * detaches it.
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
  const differing = differs(part, context.sharedParts)
  return (
    <>
      {part.shared ? (
        <Badge
          variant="outline"
          className={differing ? "text-warning" : undefined}
          title={differing ? t("steward.document-editor.shared-part-differs", { name: part.shared }) : undefined}
        >
          <PuzzlePieceIcon aria-hidden />
          {t("steward.document-editor.shared-part", { name: part.shared })}
        </Badge>
      ) : null}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label={t("steward.document-editor.shared-part-menu")}
            title={t("steward.document-editor.shared-part-menu")}
          >
            <PuzzlePieceIcon aria-hidden />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="w-auto">
          <DropdownMenuItem onSelect={() => setOpen(true)}>
            {part.shared
              ? t("steward.document-editor.update-shared-part", { name: part.shared })
              : t("steward.document-editor.save-shared-part")}
          </DropdownMenuItem>
          {part.shared ? (
            <DropdownMenuItem onSelect={() => set(unmarked)}>
              {t("steward.document-editor.detach-shared-part")}
            </DropdownMenuItem>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
      {open ? (
        <SharedPartDialog
          part={part}
          sharedParts={context.sharedParts}
          language={context.language}
          onClose={() => setOpen(false)}
          onShared={(shared) => set((it) => (shared ? { ...unmarked(it), shared } : unmarked(it)))}
        />
      ) : null}
    </>
  )
}

/** Whether a copy of a shared part was changed in this document since it was inserted or saved. */
export function differs(part: DocumentPart, sharedParts: readonly SharedPartView[]): boolean {
  const saved = sharedParts.find((each) => each.name === part.shared)
  return saved !== undefined && canonical(unmarked(part)) !== canonical(saved.part)
}

function canonical(value: unknown): string {
  return JSON.stringify(value, (_, inner: unknown) =>
    inner && typeof inner === "object" && !Array.isArray(inner)
      ? Object.fromEntries(Object.entries(inner).toSorted(([a], [b]) => a.localeCompare(b)))
      : inner,
  )
}

/**
 * Saves a part as a named shared part. Before the save it shows each saved document that holds the shared part, before
 * and after, as the renderers draw them; the save rewrites them all at once.
 */
export function SharedPartDialog({
  part,
  sharedParts,
  language,
  onClose,
  onShared,
}: {
  part: DocumentPart
  sharedParts: readonly SharedPartView[]
  language: Language
  onClose: () => void
  /** Marks the part as a copy of the shared part saved, or unmarks it after it was deleted. */
  onShared: (shared: string | undefined) => void
}) {
  const [name, setName] = useState(part.shared ?? "")
  const field = useId()
  const cancel = useRef<HTMLButtonElement>(null)
  const valid = name.length <= 64 && NAME.test(name)
  const content = unmarked(part)
  const changes = useSharedPartChanges(name, content, valid)
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
      <ResponsiveDialogContent
        className="sm:max-w-3xl"
        onOpenAutoFocus={(event) => {
          // An update has no name field, and the first button is delete.
          if (!part.shared) return
          event.preventDefault()
          cancel.current?.focus()
        }}
      >
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>
            {part.shared
              ? t("steward.document-editor.update-shared-part", { name: part.shared })
              : t("steward.document-editor.save-shared-part")}
          </ResponsiveDialogTitle>
          <ResponsiveDialogDescription>
            {t("steward.document-editor.shared-part-explained")}
          </ResponsiveDialogDescription>
        </ResponsiveDialogHeader>
        <div className="flex min-w-0 flex-col gap-3 overflow-y-auto">
          {part.shared ? null : (
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
                        <h4 className="truncate text-sm font-medium">{documentName(change.declaration)}</h4>
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
          {part.shared && exists ? (
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
          <Button ref={cancel} type="button" variant="outline" onClick={onClose}>
            {t("steward.form.cancel")}
          </Button>
          <Button
            type="button"
            disabled={!valid || !changes.data || refused || save.isPending}
            onClick={() => submit(content)}
          >
            {t("steward.document-editor.save")}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
