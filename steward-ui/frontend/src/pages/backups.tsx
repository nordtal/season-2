import { useMemo, useState } from "react"
import { Link, useNavigate, useParams } from "@tanstack/react-router"
import { ArrowCounterClockwiseIcon, ClockIcon, CloudIcon, DownloadIcon } from "@phosphor-icons/react"
import { toast } from "sonner"

import type { Backup, ConfigChanges, ConfigEntry, ParsedConfigDocument, Run } from "@/lib/api"
import { ApiError } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, count, dateTime, duration, parseInstant, relative } from "@/lib/format"
import { useBackups, useConfig, useConfigs, useRuns, useSaveConfig, useSchedule } from "@/lib/queries"
import { ScalarControl } from "@/components/steward/config-controls"
import { Actor } from "@/components/steward/entity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { RunStatus } from "@/components/steward/status"
import { Empty, Loading, QueryState, Skeleton, SkeletonText } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { AskButton, CopyButton } from "@/pages/operations"
import {
  ResponsiveDialog,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

/**
 * Everything about the backups: the numbers, the runs, the offsite target and the schedule.
 *
 * A secret is never drawn, and nothing here starts or restores anything; runs are asked for on Operations.
 */
export function BackupsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Backups"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <DestinationDialog />
            <ScheduleDialog />
            <RestoreDialog />
            <AskButton kind="BACKUP" variant="default" label="Back up now" size="sm" />
          </div>
        }
      />

      <Summary />

      <Runs />
    </div>
  )
}

/** `steward-worker/steward.yml`, looked up in `/api/config` so a missing file gets no form. */
export function useWorkerConfig() {
  const configs = useConfigs()
  const file = configs.data?.find(
    (location) => location.service === "steward-worker" && location.name.split("/").pop() === "steward.yml",
  )
  const document = useConfig(file?.path ?? "", Boolean(file))
  const parsed = document.data && !document.data.raw ? document.data : undefined
  return { file: file?.path, document: parsed, pending: configs.isPending || document.isPending }
}

/** One key of that file by its dotted path, or `undefined` when there is none. */
export function entryAt(document: ParsedConfigDocument | undefined, path: string): ConfigEntry | undefined {
  return document?.entries.find((entry) => entry.path === path)
}

/**
 * The draft, changes and save state for a fixed list of keys of one document.
 *
 * `keys` must be a stable reference, or the memo is invalidated every render.
 */
export function useConfigDraft(document: ParsedConfigDocument | undefined, keys: readonly string[]) {
  const [draft, setDraft] = useState<Record<string, string>>({})

  /** The answer to a save is the file as written, so a write resets the form to it. */
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
    /** A secret has no value to compare with, so anything typed into one is a change. */
    if (entry.secret ? typed !== "" : typed !== (entry.value ?? "")) changes[entry.path] = typed
  }
  const changed = Object.keys(changes).length

  return { entries, draft, setDraft, changes, changed }
}

/** One entry's value as it would be saved now: typed, or the file's. */
export function draftValue(entries: ConfigEntry[], draft: Record<string, string>, path: string): string {
  const typed = draft[path]
  if (typed !== undefined) return typed
  return entries.find((entry) => entry.path === path)?.value ?? ""
}

/**
 * Three numbers, roughly two to a row on a phone.
 *
 * Nothing queries the Storage Box yet, so "Storage available" says so instead of drawing a number.
 */
function Summary() {
  const backups = useBackups()
  const schedule = useSchedule()
  const { document } = useWorkerConfig()
  const at = entryAt(document, "backup.at")?.value

  const files = backups.data ?? []
  const finished = files.filter((backup) => !backup.partial)
  const newest = finished[0]

  return (
    <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-3">
      <Stat
        label="Latest backup"
        value={newest ? relative(newest.modified) : backups.data ? "none" : "\u2013"}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? "no finished backup" : "\u2013"}
      />
      {/* Neutral rather than warn, since "not tracked" is a fact about the feature, not an alarm. */}
      <Stat label="Storage available" value="not tracked" hint="steward-worker does not query the Storage Box yet" />
      <Stat
        label="Next"
        value={schedule.data?.nextBackupAt ? relative(schedule.data.nextBackupAt) : "\u2013"}
        hint={
          schedule.data?.nextBackupAt
            ? `${at ?? schedule.data.backupAt} ${schedule.data.zone}`
            : schedule.isPending
              ? "\u2013"
              : "no nightly clock"
        }
      />
    </div>
  )
}

/** The `BACKUP` rows of the worker's inbox, newest first. */
function backupRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "BACKUP")
}

/** How many of a run's report lines were saved, out of how many. */
function saved(run: Run): { saved: number; total: number } {
  const lines = run.report?.services ?? []
  return { saved: lines.filter((line) => line.state === "SAVED").length, total: lines.length }
}

function ran(run: Run): string {
  const started = parseInstant(run.started)
  const finished = parseInstant(run.finished)
  if (!started || !finished) return "\u2013"
  return duration((finished.getTime() - started.getTime()) / 1000)
}

/** Four waiting runs; the panel shows eight at most. */
const WAITING_BACKUP_RUNS = Array.from({ length: 4 }, () => undefined)

/** The last backup runs, each row a link to its detail page. */
function Runs() {
  const navigate = useNavigate()
  const runs = useRuns(40)
  const rows = backupRuns(runs.data).slice(0, 8)

  return (
    <Panel title="Runs">
      <QueryState
        query={runs}
        isEmpty={() => rows.length === 0}
        empty={{
          title: "No backup run yet",
          note: "The worker's clock and the Back up now button both land here once one has run.",
        }}
      >
        {(answer) => (
          <Table className="steward-table">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[5rem]">Run</TableHead>
                <TableHead className="w-[12rem]">When</TableHead>
                <TableHead className="w-[9rem]">Status</TableHead>
                <TableHead className="w-[7rem] text-right">Archives</TableHead>
                <TableHead className="w-[7rem] text-right">Took</TableHead>
                <TableHead>Initiated by</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(answer ? rows : WAITING_BACKUP_RUNS).map((run, index) => {
                const archives = run ? saved(run) : undefined
                return (
                  <TableRow
                    key={run?.id ?? index}
                    className={run ? "cursor-pointer" : undefined}
                    onClick={() => run && navigate({ to: "/operations/backups/$id", params: { id: String(run.id) } })}
                  >
                    <TableCell data-label="Run" className="font-medium tnum">
                      {run ? (
                        <Link
                          to="/operations/backups/$id"
                          params={{ id: String(run.id) }}
                          className="underline-offset-4 hover:text-primary hover:underline"
                          onClick={(event) => event.stopPropagation()}
                        >
                          #{run.id}
                        </Link>
                      ) : (
                        <SkeletonText width="short" />
                      )}
                    </TableCell>
                    <TableCell data-label="When">
                      {run ? dateTime(run.started || run.requested) : <SkeletonText width="long" />}
                    </TableCell>
                    <TableCell data-label="Status">
                      {run ? <RunStatus status={run.status} /> : <Skeleton className="h-5 w-20 rounded-full" />}
                    </TableCell>
                    <TableCell data-label="Archives" className="text-right tnum">
                      {archives ? (
                        archives.total === 0 ? (
                          "\u2013"
                        ) : (
                          count(archives.saved)
                        )
                      ) : (
                        <SkeletonText width="short" className="ml-auto" />
                      )}
                    </TableCell>
                    <TableCell data-label="Took" className="text-right tnum">
                      {run ? ran(run) : <SkeletonText width="short" className="ml-auto" />}
                    </TableCell>
                    <TableCell data-label="Initiated by" className="text-muted-foreground">
                      {!run ? <SkeletonText width="medium" /> : <Actor kind={run.actorKind} id={run.actorId} />}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </QueryState>
    </Panel>
  )
}

/** The five keys of `backup.remote`, in the order the form draws them. */
const REMOTE_KEYS = [
  "backup.remote.endpoint",
  "backup.remote.bucket",
  "backup.remote.prefix",
  "backup.remote.access-key",
  "backup.remote.secret-key",
] as const

/**
 * Where a copy goes that is not on this disk.
 *
 * Saves five keys of `steward.yml` with its revision; the two keys are secrets never read back.
 */
function DestinationDialog() {
  const { file, document, pending } = useWorkerConfig()
  const save = useSaveConfig(file ?? "")
  const { entries, draft, setDraft, changes, changed } = useConfigDraft(document, REMOTE_KEYS)

  return (
    <ResponsiveDialog>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <CloudIcon />
          Destination
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Destination</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>Where a copy goes that is not on this disk.</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {pending ? (
          <Loading rows={3} />
        ) : !document || entries.length === 0 ? (
          <Empty
            title="This worker's config has no backup.remote section"
            note="The file in the volume predates it. A worker that has started since the section was added writes it in."
          />
        ) : (
          <div className="flex flex-col gap-4">
            {entries.map((entry) => (
              <div key={entry.path} className="flex flex-col gap-1.5">
                <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
                <ScalarControl
                  id={entry.path}
                  entry={entry}
                  value={draft[entry.path] ?? (entry.secret ? "" : (entry.value ?? ""))}
                  edited={draft[entry.path] !== undefined}
                  disabled={!document.writable || save.isPending}
                  roles={undefined}
                  channels={undefined}
                  onChange={(value) => setDraft((was) => ({ ...was, [entry.path]: value }))}
                />
              </div>
            ))}

            <div className="flex items-center gap-3">
              <Button
                disabled={changed === 0 || !document.writable || save.isPending}
                onClick={() =>
                  save.mutate(
                    { revision: document.revision, changes },
                    {
                      onSuccess: () => toast.success("Destination saved."),
                      onError: (failure) =>
                        toast.error(
                          failure instanceof ApiError && failure.status === 409
                            ? "The file changed while this was open. It has been read again."
                            : String(failure),
                        ),
                    },
                  )
                }
              >
                Save
              </Button>
              {changed > 0 ? <span className="text-sm text-muted-foreground tnum">{changed} changed</span> : null}
            </div>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/** `backup.at` and the four `backup.retention` keys, in the order the dialog draws them. */
const SCHEDULE_KEYS = [
  "backup.at",
  "backup.retention.daily",
  "backup.retention.weekly",
  "backup.retention.monthly",
  "backup.retention.collapse-after-days",
] as const

/** The seven days in week order, with their `java.time.DayOfWeek` names. */
const WEEKDAYS = [
  { label: "Mon", value: "MONDAY" },
  { label: "Tue", value: "TUESDAY" },
  { label: "Wed", value: "WEDNESDAY" },
  { label: "Thu", value: "THURSDAY" },
  { label: "Fri", value: "FRIDAY" },
  { label: "Sat", value: "SATURDAY" },
  { label: "Sun", value: "SUNDAY" },
] as const

/** The `backup.days` key, a list and therefore not part of the scalar draft. */
const DAYS_KEY = "backup.days"

/** Which days a list of config entries means, by the first three letters, as `NightlyClock` reads them. */
export function chosenDays(items: string[] | undefined): string[] {
  if (items === undefined) return []
  const stems = new Set(items.map((item) => item.trim().slice(0, 3).toUpperCase()))
  return WEEKDAYS.filter((day) => stems.has(day.value.slice(0, 3))).map((day) => day.value)
}

/** The seven days as toggles, in week order; shared by the backup and update schedules. */
export function DayPicker({
  days,
  disabled,
  onChange,
}: {
  days: string[]
  disabled: boolean
  onChange: (days: string[]) => void
}) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {WEEKDAYS.map((day) => {
        const on = days.includes(day.value)
        return (
          <Badge
            key={day.value}
            asChild
            variant={on ? "default" : "outline"}
            className={disabled ? undefined : "cursor-pointer"}
          >
            <button
              type="button"
              aria-pressed={on}
              disabled={disabled}
              onClick={() =>
                onChange(
                  on
                    ? days.filter((chosen) => chosen !== day.value)
                    : WEEKDAYS.filter(
                        (candidate) => candidate.value === day.value || days.includes(candidate.value),
                      ).map((candidate) => candidate.value),
                )
              }
            >
              {day.label}
            </button>
          </Badge>
        )
      })}
    </div>
  )
}

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
  const steps = [`the last ${count(daily)} day${daily === 1 ? "" : "s"} in full`]
  if (weekly > 0) steps.push(`one a week for ${count(weekly)} more week${weekly === 1 ? "" : "s"}`)
  if (monthly > 0) steps.push(`one a month for ${count(monthly)} more month${monthly === 1 ? "" : "s"}`)
  const total = daily + weekly + monthly
  const grace =
    collapseAfterDays === 0
      ? "collapses to the last run of that day on the very next sweep"
      : `collapses to the last run of that day after ${count(collapseAfterDays)} day${collapseAfterDays === 1 ? "" : "s"}`
  return `Keeps ${steps.join(", then ")} - at most ${count(total)} archives per volume. A day with several runs on it ${grace}.`
}

/**
 * The nightly clock and its retention, in a dialog.
 *
 * The weekday badges write the `backup.days` list, kept beside the scalar draft.
 */
function ScheduleDialog() {
  const { file, document, pending } = useWorkerConfig()
  const save = useSaveConfig(file ?? "")
  const { entries, draft, setDraft, changes, changed } = useConfigDraft(document, SCHEDULE_KEYS)

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

  const daily = intOr(draftValue(entries, draft, "backup.retention.daily"), 14)
  const weekly = intOr(draftValue(entries, draft, "backup.retention.weekly"), 0)
  const monthly = intOr(draftValue(entries, draft, "backup.retention.monthly"), 0)
  const collapseAfterDays = intOr(draftValue(entries, draft, "backup.retention.collapse-after-days"), 3)

  return (
    <ResponsiveDialog>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <ClockIcon />
          Schedule
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Schedule</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>When a backup runs, and how long it is kept.</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {pending ? (
          <Loading rows={5} />
        ) : !document || entries.length === 0 ? (
          <Empty
            title="This worker's config has no backup.retention section"
            note="The file in the volume predates it. A worker that has started since the section was added writes it in."
          />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label>Days</Label>
              <DayPicker
                days={days}
                disabled={!daysEntry || !document.writable || save.isPending}
                onChange={setPickedDays}
              />
              {!daysEntry ? (
                <p className="text-xs text-muted-foreground">
                  This worker's config has no backup.days yet. A worker that has started since the key was added writes
                  it in.
                </p>
              ) : days.length === 0 ? (
                <p className="text-xs text-destructive">No night is picked, so no backup runs.</p>
              ) : null}
            </div>

            {entries.map((entry) => (
              <div key={entry.path} className="flex flex-col gap-1.5">
                <Label htmlFor={entry.path}>{entry.label || entry.key}</Label>
                <ScalarControl
                  id={entry.path}
                  entry={entry}
                  value={draft[entry.path] ?? (entry.secret ? "" : (entry.value ?? ""))}
                  edited={draft[entry.path] !== undefined}
                  disabled={!document.writable || save.isPending}
                  roles={undefined}
                  channels={undefined}
                  onChange={(value) => setDraft((was) => ({ ...was, [entry.path]: value }))}
                />
              </div>
            ))}

            <p className="text-sm text-muted-foreground">
              {retentionSentence(daily, weekly, monthly, collapseAfterDays)}
            </p>

            <div className="flex items-center gap-3">
              <Button
                disabled={allChanged === 0 || !document.writable || save.isPending}
                onClick={() =>
                  save.mutate(
                    { revision: document.revision, changes: allChanges },
                    {
                      onSuccess: () => toast.success("Schedule saved."),
                      onError: (failure) =>
                        toast.error(
                          failure instanceof ApiError && failure.status === 409
                            ? "The file changed while this was open. It has been read again."
                            : String(failure),
                        ),
                    },
                  )
                }
              >
                Save
              </Button>
              {allChanged > 0 ? <span className="text-sm text-muted-foreground tnum">{allChanged} changed</span> : null}
            </div>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/**
 * Builds the restore command for a person to run on the host, where it still works with the stack down.
 *
 * Only finished archives are offered.
 */
function RestoreDialog() {
  const backups = useBackups()
  const [chosen, setChosen] = useState<string>("")
  const restorable = useMemo(() => (backups.data ?? []).filter((backup) => !backup.partial), [backups.data])
  const command = `sudo bash deploy/restore.sh ${chosen || "<archive>"}`

  return (
    <ResponsiveDialog>
      <ResponsiveDialogTrigger asChild>
        <Button variant="outline" size="sm">
          <ArrowCounterClockwiseIcon />
          Restore
        </Button>
      </ResponsiveDialogTrigger>
      <ResponsiveDialogContent>
        <ResponsiveDialogHeader>
          <ResponsiveDialogTitle>Restore</ResponsiveDialogTitle>
          <ResponsiveDialogDescription>Run on the host. Steward does not run it.</ResponsiveDialogDescription>
        </ResponsiveDialogHeader>

        {backups.isPending || backups.error ? (
          <QueryState query={backups} rows={2}>
            {() => null}
          </QueryState>
        ) : restorable.length === 0 ? (
          <Empty
            title="No archive to restore"
            note={
              (backups.data ?? []).length > 0
                ? "Every file still carries the .partial suffix."
                : "The backup directory is empty."
            }
          />
        ) : (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="restore-archive">Archive</Label>
              <Select value={chosen} onValueChange={setChosen}>
                <SelectTrigger id="restore-archive" className="w-full">
                  <SelectValue placeholder="Choose an archive…" />
                </SelectTrigger>
                <SelectContent>
                  {restorable.map((backup) => (
                    <SelectItem key={backup.name} value={backup.name}>
                      {backup.name} ({bytes(backup.bytes)}, {relative(backup.modified)})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 overflow-x-auto rounded-md border border-border bg-[#0a0a0a] px-3 py-2 font-mono text-xs whitespace-pre">
                {command}
              </code>
              <CopyButton text={command} disabled={!chosen} />
            </div>
            <p className="text-xs text-warning">
              A volume is overwritten, not added to - everything made since the backup is gone.
            </p>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

/**
 * The archives one run wrote: those modified within a minute of the run's start and finish.
 *
 * A run that cannot be dated matches nothing.
 */
function archivesOf(run: Run, backups: Backup[]): Backup[] {
  const started = parseInstant(run.started)?.getTime()
  const finished = parseInstant(run.finished)?.getTime()
  if (started === undefined || finished === undefined || started === null || finished === null) {
    return []
  }
  const from = started - 60_000
  const to = finished + 60_000
  return backups
    .filter((backup) => {
      const modified = parseInstant(backup.modified)?.getTime()
      return modified !== undefined && modified !== null && modified >= from && modified <= to
    })
    .toSorted((left, right) => left.name.localeCompare(right.name))
}

/** What one file is, from its name, with `.partial` said plainly. */
function holds(backup: Backup): string {
  const what = archived(backup.name)
  const subject = what.kind === "database" ? "database" : (what.subject ?? "unknown")
  return backup.partial ? `${subject}, partial` : subject
}

/** Three waiting archives, the usual number for one run. */
const WAITING_ARCHIVES = Array.from({ length: 3 }, () => undefined)

/** One backup run and the archives it wrote, each downloadable. */
export function BackupRunDetailPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  const runs = useRuns(80)
  const backups = useBackups()
  const run = runs.data?.find((candidate) => String(candidate.id) === id)
  const files = run ? archivesOf(run, backups.data ?? []) : []

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={`Backup #${id}`} />

      <QueryState
        query={runs}
        isEmpty={() => !run}
        empty={{
          title: "No such run",
          note: "It may be older than this page's own window.",
        }}
      >
        {(answer) => (
          <>
            <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-4">
              {/* The four figures keep their places while the run is looked up. */}
              <Stat label="Status" value={run ? <RunStatus status={run.status} /> : undefined} />
              <Stat label="When" value={run ? dateTime(run.started || run.requested) : undefined} />
              <Stat label="Took" value={run ? ran(run) : undefined} />
              <Stat label="Archives" value={answer && backups.data ? count(files.length) : undefined} />
            </div>

            <Panel title="Archives">
              {answer && backups.data && files.length === 0 ? (
                <Empty title="No archive matched this run's own window" />
              ) : (
                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead>Archive</TableHead>
                      <TableHead className="w-[9rem]">Holds</TableHead>
                      <TableHead className="w-[12rem]">Written</TableHead>
                      <TableHead className="w-[8rem] text-right">Size</TableHead>
                      <TableHead className="w-[3rem]" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(answer && backups.data ? files : WAITING_ARCHIVES).map((backup, index) => (
                      <TableRow key={backup?.name ?? index}>
                        <TableCell data-label="Archive">
                          {backup ? (
                            <code className="text-xs">{backup.name}</code>
                          ) : (
                            <SkeletonText className="text-xs" width="long" />
                          )}
                        </TableCell>
                        <TableCell data-label="Holds">
                          {backup ? holds(backup) : <SkeletonText width="medium" />}
                        </TableCell>
                        <TableCell data-label="Written">
                          {backup ? dateTime(backup.modified) : <SkeletonText width="long" />}
                        </TableCell>
                        <TableCell data-label="Size" className="text-right tnum">
                          {backup ? bytes(backup.bytes) : <SkeletonText width="short" className="ml-auto" />}
                        </TableCell>
                        <TableCell data-label="Download">
                          {!backup || backup.partial ? null : (
                            <a
                              href={`/api/backups/${encodeURIComponent(backup.name)}/download`}
                              download={backup.name}
                              aria-label={`Download ${backup.name}`}
                              className="inline-flex text-muted-foreground hover:text-foreground"
                            >
                              <DownloadIcon className="size-4" />
                            </a>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Panel>
          </>
        )}
      </QueryState>
    </div>
  )
}
