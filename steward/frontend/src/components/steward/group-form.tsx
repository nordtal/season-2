import { useMemo, useState } from "react"

import { TERM_ICON } from "@/components/steward/term-icons"
import type { ConfigChanges, ConfigEntry, ConfigDocument } from "@/lib/api"
import { announceSave } from "@/lib/announce-save"
import { useConfig, useConfigs, useSaveConfig } from "@/lib/queries"
import { t } from "@/lib/texts"
import { ScalarControl } from "@/components/steward/config-controls"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  ResponsiveDialog,
  ResponsiveDialogClose,
  ResponsiveDialogContent,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog"

/** A group that is not published yet gets no form. */
export function useStewardConfig() {
  return useGroupConfig("steward", "steward")
}

/** A group that is not published yet gets no form. */
function useGroupConfig(service: string, name: string) {
  const configs = useConfigs()
  const file = configs.data?.find((location) => location.service === service && location.name === name)
  const document = useConfig(file?.path ?? "", Boolean(file))
  return { file: file?.path, document: document.data, pending: configs.isPending || document.isPending }
}

export function entryAt(document: ConfigDocument | undefined, path: string): ConfigEntry | undefined {
  return document?.entries.find((entry) => entry.path === path)
}

/** `keys` must be a stable reference, or the memo is invalidated on every render. */
function useConfigDraft(document: ConfigDocument | undefined, keys: readonly string[]) {
  const [draft, setDraft] = useState<Record<string, string>>({})

  /** The answer to a save is the group as stored, so a write resets the form to it. */
  const [lastDocument, setLastDocument] = useState(document)
  if (lastDocument !== document) {
    setLastDocument(document)
    setDraft({})
  }

  const entries = useMemo(
    () => keys.map((path) => entryAt(document, path)).filter((entry): entry is ConfigEntry => entry !== undefined),
    [document, keys],
  )

  const changes: ConfigChanges = {}
  for (const entry of entries) {
    const typed = draft[entry.path]
    if (typed === undefined) continue
    if (!entry.secret && typed !== (entry.value ?? "")) changes[entry.path] = typed
  }
  const changed = Object.keys(changes).length

  return { entries, draft, setDraft, changes, changed }
}

const WEEKDAYS = [
  { label: () => t("steward.form.weekday", { day: "monday" }), value: "MONDAY" },
  { label: () => t("steward.form.weekday", { day: "tuesday" }), value: "TUESDAY" },
  { label: () => t("steward.form.weekday", { day: "wednesday" }), value: "WEDNESDAY" },
  { label: () => t("steward.form.weekday", { day: "thursday" }), value: "THURSDAY" },
  { label: () => t("steward.form.weekday", { day: "friday" }), value: "FRIDAY" },
  { label: () => t("steward.form.weekday", { day: "saturday" }), value: "SATURDAY" },
  { label: () => t("steward.form.weekday", { day: "sunday" }), value: "SUNDAY" },
] as const

/** It reads them by their first three letters, as `NightlyClock` does. */
function chosenDays(items: string[] | undefined): string[] {
  if (items === undefined) return []
  const stems = new Set(items.map((item) => item.trim().slice(0, 3).toUpperCase()))
  return WEEKDAYS.filter((day) => stems.has(day.value.slice(0, 3))).map((day) => day.value)
}

function DayPicker({
  days,
  disabled,
  labelledBy,
  onChange,
}: {
  days: string[]
  disabled: boolean
  labelledBy: string
  onChange: (days: string[]) => void
}) {
  return (
    <div role="group" aria-labelledby={labelledBy} className="grid grid-cols-7 gap-1.5">
      {WEEKDAYS.map((day) => {
        const on = days.includes(day.value)
        return (
          <Button
            key={day.value}
            type="button"
            size="sm"
            className="px-0"
            variant={on ? "default" : "outline"}
            aria-pressed={on}
            disabled={disabled}
            onClick={() =>
              onChange(
                on
                  ? days.filter((chosen) => chosen !== day.value)
                  : WEEKDAYS.filter((candidate) => candidate.value === day.value || days.includes(candidate.value)).map(
                      (candidate) => candidate.value,
                    ),
              )
            }
          >
            {day.label()}
          </Button>
        )
      })}
    </div>
  )
}

/** A form footer's count of changed fields, shown once one has changed. */
export function ChangedCount({ count }: { count: number }) {
  if (count === 0) return null
  return (
    // Last in the sheet's reversed column, so the count stands above Save as it stands beside it on a desktop.
    <span className="self-center text-sm text-muted-foreground tnum max-sm:order-last sm:mr-auto">
      {t("steward.form.changed", { count })}
    </span>
  )
}

const NO_KEYS: readonly string[] = []

/**
 * Edits a clock of Steward's group: its weekdays under `<job>.days` and its time under `<job>.at`, plus the keys of
 * one more group (`also`). Save writes each group that changed, and the dialog closes once every write is stored.
 */
export function ScheduleDialog({
  job,
  empty,
  noDay,
  also,
}: {
  job: "backup" | "update"
  /** Shown when Steward's group has no `<job>.at`. */
  empty: string
  /** Shown while a time is set and no day is picked. */
  noDay: string
  also?: { service: string; name: string; keys: readonly string[]; saved: string }
}) {
  const atKeys = useMemo(() => [`${job}.at`], [job])
  const daysKey = `${job}.days`
  const [open, setOpen] = useState(false)

  const { file, document, pending } = useStewardConfig()
  const save = useSaveConfig(file ?? "")
  const own = useConfigDraft(document, atKeys)
  const other = useGroupConfig(also?.service ?? "", also?.name ?? "")
  const saveOther = useSaveConfig(other.file ?? "")
  const otherDraft = useConfigDraft(other.document, also?.keys ?? NO_KEYS)

  /** `undefined` until a badge is clicked, so the file's list is drawn. */
  const [pickedDays, setPickedDays] = useState<string[] | undefined>(undefined)
  const [lastDocument, setLastDocument] = useState(document)
  if (lastDocument !== document) {
    setLastDocument(document)
    setPickedDays(undefined)
  }

  const daysEntry = entryAt(document, daysKey)
  const fileDays = chosenDays(daysEntry?.items)
  const days = pickedDays ?? fileDays
  const daysChanged = pickedDays !== undefined && pickedDays.join() !== fileDays.join()
  const allChanges: ConfigChanges = daysChanged ? { ...own.changes, [daysKey]: days } : own.changes
  const ownCount = own.changed + (daysChanged ? 1 : 0)
  const count = ownCount + otherDraft.changed
  const at = own.draft[`${job}.at`] ?? entryAt(document, `${job}.at`)?.value ?? ""

  const saving = save.isPending || saveOther.isPending
  const writable = Boolean(document?.writable) && (otherDraft.changed === 0 || Boolean(other.document?.writable))

  function toggle(next: boolean) {
    if (saving) return
    if (next) {
      save.reset()
      saveOther.reset()
    }
    setOpen(next)
  }

  async function submit() {
    try {
      if (ownCount > 0 && document) {
        const saved = await save.mutateAsync({ revision: document.revision, changes: allChanges })
        announceSave(t("steward.form.schedule-saved"), saved.reload)
      }
      if (also && otherDraft.changed > 0 && other.document) {
        const saved = await saveOther.mutateAsync({ revision: other.document.revision, changes: otherDraft.changes })
        announceSave(also.saved, saved.reload)
      }
      setOpen(false)
    } catch {
      /** The failed mutation's error is drawn in the dialog. */
    }
  }

  const fields = (group: ReturnType<typeof useConfigDraft>, writableGroup: boolean) =>
    group.entries.map((entry) => (
      <div key={entry.path} className="flex flex-col gap-1.5">
        <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
        <ScalarControl
          id={entry.path}
          entry={entry}
          value={group.draft[entry.path] ?? (entry.secret ? "" : (entry.value ?? ""))}
          disabled={!writableGroup || saving}
          onChange={(value) => group.setDraft((was) => ({ ...was, [entry.path]: value }))}
        />
      </div>
    ))

  const failure = save.error ?? saveOther.error

  return (
    <ResponsiveDialog open={open} onOpenChange={toggle}>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <TERM_ICON.schedule />
          {t("steward.form.schedule")}
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent aria-describedby={undefined}>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("steward.form.schedule")}</ResponsiveDialogTitle>
        </ResponsiveDialogHeader>

        {pending || (also && other.pending) ? (
          <Loading rows={also ? 5 : 3} />
        ) : !document || own.entries.length === 0 ? (
          <Empty title={empty} />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label id={`${daysKey}-label`}>{t("steward.form.days")}</Label>
              <DayPicker
                days={days}
                labelledBy={`${daysKey}-label`}
                disabled={!daysEntry || !document.writable || saving}
                onChange={setPickedDays}
              />
              {at.trim() !== "" && days.length === 0 ? <p className="text-xs text-destructive">{noDay}</p> : null}
            </div>
            {fields(own, document.writable)}
            {fields(otherDraft, Boolean(other.document?.writable))}
            {failure ? <Failure error={failure} write /> : null}
          </div>
        )}

        <ResponsiveDialogFooter>
          <ChangedCount count={count} />
          <ResponsiveDialogClose asChild>
            <Button type="button" variant="outline" disabled={saving}>
              {t("steward.form.cancel")}
            </Button>
          </ResponsiveDialogClose>
          <Button type="button" disabled={count === 0 || !writable || saving} onClick={() => void submit()}>
            {saving ? t("steward.form.saving") : t("steward.form.save")}
          </Button>
        </ResponsiveDialogFooter>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
