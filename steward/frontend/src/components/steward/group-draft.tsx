import { type ReactNode, useMemo } from "react"
import type { UseMutationResult } from "@tanstack/react-query"

import type { ConfigChanges, ConfigDocument, ReloadOutcome } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { useSaveConfig } from "@/lib/queries"
import { Button } from "@/components/ui/button"
import { changed, type Draft } from "@/components/steward/configuration"
import { Failure } from "@/components/steward/query-state"
import { PinnedRow } from "@/components/steward/settings-view"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { t } from "@/lib/texts"

export type DraftValue = string | string[] | SectionValues[]

function isSectionValues(value: unknown): value is SectionValues {
  return (
    typeof value === "object" &&
    value !== null &&
    Object.values(value).every(
      (entry) =>
        typeof entry === "string" ||
        (Array.isArray(entry) && entry.every((item) => typeof item === "string" || isSectionValues(item))),
    )
  )
}

function isDraftValue(value: unknown): value is DraftValue {
  return (
    typeof value === "string" ||
    (Array.isArray(value) && value.every((item) => typeof item === "string")) ||
    (Array.isArray(value) && value.every(isSectionValues))
  )
}

function isDraftValueRecord(value: unknown): value is Record<string, DraftValue> {
  return typeof value === "object" && value !== null && Object.values(value).every(isDraftValue)
}

/** A Nordtal group's save or a plugin file's: both take the form's changes and answer how they apply. */
export type GroupSave = Pick<UseMutationResult, "isPending" | "error"> & {
  mutate: (
    edit: { revision: string; changes: ConfigChanges },
    options: { onSuccess: (saved: { reload?: ReloadOutcome }) => void },
  ) => void
}

/**
 * A value set back to the stored value leaves the draft, so the count is the number of changes a save makes.
 */
export function useGroupDraft(file: string, document: ConfigDocument, save: GroupSave) {
  const draft = useDraft<DraftValue>(file, isDraftValueRecord) as Draft
  const byPath = useMemo(() => new Map(document.entries.map((entry) => [entry.path, entry])), [document.entries])
  const changes = useMemo(() => changed(document, draft), [document, draft])
  const count = Object.keys(changes).length

  function set(path: string, value: DraftValue | undefined) {
    const entry = byPath.get(path)
    const same =
      value === undefined ||
      (entry !== undefined && Object.keys(changed({ ...document, entries: [entry] }, { [path]: value })).length === 0)
    setDraftValue(file, path, same ? undefined : value)
  }

  function submit() {
    const label = t("steward.settings.settings-saved", { count })
    save.mutate(
      { revision: document.revision, changes },
      {
        onSuccess: (saved) => {
          clearDraft(file)
          announceSave(label, saved.reload, document.name)
        },
      },
    )
  }

  return { draft, changes, count, set, submit, discard: () => clearDraft(file), save }
}

export function noticeOf(document: ConfigDocument): string | null {
  if (document.problem) return t("steward.settings.refused", { problem: document.problem })
  if (!document.writable) return t("steward.settings.read-only")
  return document.restartRequired ? t("steward.settings.restart-needed") : null
}

export function SaveDraft({
  count,
  writable,
  pending,
  onSave,
  onDiscard,
}: {
  count: number
  writable: boolean
  pending: boolean
  onSave: () => void
  onDiscard: () => void
}) {
  if (count === 0 || !writable) return null
  return (
    <>
      <Button type="button" variant="secondary" className="pointer-events-auto" disabled={pending} onClick={onDiscard}>
        {t("steward.form.discard")}
      </Button>
      <Button type="button" className="pointer-events-auto" disabled={pending} onClick={onSave}>
        {pending ? t("steward.form.saving") : t("steward.form.save-count", { count })}
      </Button>
    </>
  )
}

/** A custom editor's frame: the group's notice and a refused save above `children`, the save row pinned below. */
export function GroupEditor({
  file,
  document,
  children,
}: {
  file: string
  document: ConfigDocument
  children: (draft: Draft, set: (path: string, value: DraftValue | undefined) => void) => ReactNode
}) {
  const { draft, count, set, submit, discard, save } = useGroupDraft(file, document, useSaveConfig(document.path))
  const notice = noticeOf(document)
  return (
    <div className="flex flex-col gap-3">
      {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      {save.error ? <Failure error={save.error} /> : null}
      {children(draft, set)}
      <PinnedRow>
        <SaveDraft
          count={count}
          writable={document.writable}
          pending={save.isPending}
          onSave={submit}
          onDiscard={discard}
        />
      </PinnedRow>
    </div>
  )
}
