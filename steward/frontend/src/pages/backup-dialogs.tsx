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
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog"
import {
  DayPicker,
  chosenDays,
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

/** A refused save, named as the stale revision it usually is. */
function failed(failure: unknown) {
  toast.error(
    failure instanceof ApiError && failure.status === 409 ? t("steward.form.changed-meanwhile") : String(failure),
  )
}

/**
 * The nightly clock and its retention (at most daily + weekly + monthly archives a volume), in a dialog.
 *
 * Clock in steward's group, retention in steward-agent's `runs` group: Save writes each that changed.
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

  const saving = save.isPending || saveRuns.isPending

  return (
    <ResponsiveDialog>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <ClockIcon />
          {t("steward.form.schedule")}
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent aria-describedby={undefined}>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>{t("steward.form.schedule")}</ResponsiveDialogTitle>
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
