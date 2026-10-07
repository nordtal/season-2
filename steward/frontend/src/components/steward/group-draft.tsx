import { useMemo } from "react"

import type { ConfigDocument } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { clearDraft, setDraftValue, useDraft } from "@/lib/drafts"
import { useSaveConfig } from "@/lib/queries"
import { Button } from "@/components/ui/button"
import { changed, type Draft } from "@/components/steward/configuration"
import type { SectionValues } from "@/components/steward/repeatable-cards"
import { t } from "@/lib/texts"

/** What one key of a group's draft can hold: text, a list, or a list of sections. */
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

/**
 * A group's unsaved edits and the save that sends them, for the schema form and every custom editor alike.
 *
 * A value set back to what is stored leaves the draft, so the count is what a save would change.
 */
export function useGroupDraft(file: string, document: ConfigDocument) {
  const draft = useDraft<DraftValue>(file, isDraftValueRecord) as Draft
  const save = useSaveConfig(document.path)
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

/** What stands above a group's fields: why a save is refused, cannot happen, or waits for a restart. */
export function noticeOf(document: ConfigDocument): string | null {
  if (document.problem) return t("steward.settings.refused", { problem: document.problem })
  if (!document.writable) return t("steward.settings.read-only")
  return document.restartRequired ? t("steward.settings.restart-needed") : null
}

/**
 * The save of a draft and, beside it, the discard that drops all of it at once, shown only while there is something
 * to save.
 */
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
