import { useState } from "react"
import { ClockIcon } from "@phosphor-icons/react"
import { toast } from "sonner"

import type { ConfigChanges } from "@/lib/api"
import { ApiError } from "@/lib/api"
import { t } from "@/lib/texts"
import { useSaveConfig } from "@/lib/queries"
import { ScalarControl } from "@/components/steward/config-controls"
import { Empty, Loading } from "@/components/steward/query-state"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog"
import {
  DayPicker,
  chosenDays,
  draftValue,
  entryAt,
  useConfigDraft,
  useGroupConfig,
  useStewardConfig,
} from "@/components/steward/group-form"

/** steward's `backup.at`, the clock that writes the request row. */
const SCHEDULE_KEYS = ["backup.at"] as const

/** steward-agent's four `backup.retention` keys, in the order the dialog draws them; the run applies them. */
const RETENTION_KEYS = [
  "backup.retention.daily",
  "backup.retention.weekly",
  "backup.retention.monthly",
  "backup.retention.collapse-after-days",
] as const

const DAYS_KEY = "backup.days"

/** A whole number out of a draft, or `otherwise` when it does not parse. */
function intOr(value: string, otherwise: number): number {
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : otherwise
}

/**
 * What the retention numbers mean in one sentence, matched to `Retention.expired`.
 *
 * At most daily + weekly + monthly are kept; same-day runs collapse after the grace period.
 */
function retentionSentence(daily: number, weekly: number, monthly: number, collapseAfterDays: number): string {
  const steps = [t("steward.backup-settings.daily", { days: daily })]
  if (weekly > 0) steps.push(t("steward.backup-settings.weekly", { weeks: weekly }))
  if (monthly > 0) steps.push(t("steward.backup-settings.monthly", { months: monthly }))
  return t("steward.backup-settings.retention", {
    steps,
    total: daily + weekly + monthly,
    sweep: collapseAfterDays === 0,
    days: collapseAfterDays,
  })
}

/** A refused save, named as the stale revision it usually is. */
function failed(failure: unknown) {
  toast.error(
    failure instanceof ApiError && failure.status === 409 ? t("steward.form.changed-meanwhile") : String(failure),
  )
}

/**
 * The nightly clock and its retention, in a dialog.
 *
 * The clock is steward's group and the retention steward-agent's `runs` group, so Save writes each one that changed.
 * The weekday badges write the `backup.days` list, kept beside the scalar draft.
 */
export function ScheduleDialog() {
  const { file, document, pending } = useStewardConfig()
  const save = useSaveConfig(file ?? "")
  const { entries, draft, setDraft, changes, changed } = useConfigDraft(document, SCHEDULE_KEYS)
  const runs = useGroupConfig("steward-agent", "runs")
  const saveRuns = useSaveConfig(runs.file ?? "")
  const retention = useConfigDraft(runs.document, RETENTION_KEYS)

  /** The picked days; `undefined` until a badge is clicked, so the file's list is drawn. */
  const [pickedDays, setPickedDays] = useState<string[] | undefined>(undefined)
  const [lastScheduleDocument, setLastScheduleDocument] = useState(document)
  if (lastScheduleDocument !== document) {
    setLastScheduleDocument(document)
    setPickedDays(undefined)
  }

  const daysEntry = entryAt(document, DAYS_KEY)
  const fileDays = chosenDays(daysEntry?.items)
  const days = pickedDays ?? fileDays
  const daysChanged = pickedDays !== undefined && pickedDays.join() !== fileDays.join()
  const allChanges: ConfigChanges = daysChanged ? { ...changes, [DAYS_KEY]: days } : changes
  const allChanged = changed + (daysChanged ? 1 : 0)

  const kept = (path: string, otherwise: number) =>
    intOr(draftValue(retention.entries, retention.draft, path), otherwise)
  const daily = kept("backup.retention.daily", 14)
  const weekly = kept("backup.retention.weekly", 0)
  const monthly = kept("backup.retention.monthly", 0)
  const collapseAfterDays = kept("backup.retention.collapse-after-days", 3)
  const saving = save.isPending || saveRuns.isPending

  return (
    <ResponsiveDialog>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <ClockIcon />
          {t("steward.form.schedule")}
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("steward.form.schedule")}</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>{t("steward.backup-settings.schedule-note")}</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {pending || runs.pending ? (
          <Loading rows={5} />
        ) : !document || entries.length === 0 ? (
          <Empty title={t("steward.backup-settings.no-at")} />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label>{t("steward.form.days")}</Label>
              <DayPicker
                days={days}
                disabled={!daysEntry || !document.writable || save.isPending}
                onChange={setPickedDays}
              />
              {!daysEntry ? (
                <p className="text-xs text-muted-foreground">{t("steward.backup-settings.no-days")}</p>
              ) : days.length === 0 ? (
                <p className="text-xs text-destructive">{t("steward.backup-settings.no-night")}</p>
              ) : null}
            </div>

            {entries.map((entry) => (
              <div key={entry.path} className="flex flex-col gap-1.5">
                <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
                <ScalarControl
                  id={entry.path}
                  entry={entry}
                  value={draft[entry.path] ?? (entry.secret ? "" : (entry.value ?? ""))}
                  disabled={!document.writable || saving}
                  onChange={(value) => setDraft((was) => ({ ...was, [entry.path]: value }))}
                />
              </div>
            ))}

            {retention.entries.map((entry) => (
              <div key={entry.path} className="flex flex-col gap-1.5">
                <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
                <ScalarControl
                  id={entry.path}
                  entry={entry}
                  value={retention.draft[entry.path] ?? entry.value ?? ""}
                  disabled={!runs.document?.writable || saving}
                  onChange={(value) => retention.setDraft((was) => ({ ...was, [entry.path]: value }))}
                />
              </div>
            ))}

            {retention.entries.length > 0 ? (
              <p className="text-sm text-muted-foreground">
                {retentionSentence(daily, weekly, monthly, collapseAfterDays)}
              </p>
            ) : null}

            <div className="flex items-center gap-3">
              <Button
                disabled={allChanged + retention.changed === 0 || !document.writable || saving}
                onClick={() => {
                  if (allChanged > 0) {
                    save.mutate(
                      { revision: document.revision, changes: allChanges },
                      { onSuccess: () => toast.success(t("steward.form.schedule-saved")), onError: failed },
                    )
                  }
                  if (retention.changed > 0 && runs.document) {
                    saveRuns.mutate(
                      { revision: runs.document.revision, changes: retention.changes },
                      { onSuccess: () => toast.success(t("steward.backup-settings.retention-saved")), onError: failed },
                    )
                  }
                }}
              >
                {t("steward.form.save")}
              </Button>
              {allChanged + retention.changed > 0 ? (
                <span className="text-sm text-muted-foreground tnum">
                  {t("steward.form.changed", { count: allChanged + retention.changed })}
                </span>
              ) : null}
            </div>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
