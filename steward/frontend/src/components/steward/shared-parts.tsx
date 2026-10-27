import { PuzzlePieceIcon } from "@phosphor-icons/react"
import { useId, useState } from "react"

import type { SharedPartView, DocumentPart } from "@/lib/api"
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
  return (
    <>
      {part.shared ? (
        <Badge
          variant="outline"
          className="min-w-0 shrink"
          title={t("steward.document-editor.shared-part", { name: part.shared })}
        >
          <PuzzlePieceIcon aria-hidden />
          <span className="truncate">{t("steward.document-editor.shared-part", { name: part.shared })}</span>
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
  sharedParts,
  language,
  onClose,
  onShared,
}: {
  part: DocumentPart
  sharedParts: readonly SharedPartView[]
  language: Language
  onClose: () => void
  /** Makes the part name the shared part saved; `undefined` after the shared part was deleted. */
  onShared: (shared: string | undefined) => void
}) {
  const [name, setName] = useState("")
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
          <ResponsiveDialogTitle>{t("steward.document-editor.save-shared-part")}</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>
        <div className="flex min-w-0 flex-col gap-3 overflow-y-auto">
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
            <span id={`${field}-rule`} className={name !== "" && !valid ? "text-destructive" : "text-muted-foreground"}>
              {t("steward.document-editor.shared-part-name-rule")}
            </span>
            {exists ? (
              <span className="text-warning">{t("steward.document-editor.shared-part-exists", { name })}</span>
            ) : null}
          </div>
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
