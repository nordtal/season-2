import { useEffect, useMemo, useState } from "react"
import { Link, useNavigate, useParams } from "@tanstack/react-router"
import { ClockIcon, CloudIcon, DownloadIcon } from "@phosphor-icons/react"
import { toast } from "sonner"

import type { Backup, ConfigChanges, ConfigEntry, ParsedConfigDocument, Run } from "@/lib/api"
import { ApiError } from "@/lib/api"
import { archived } from "@/lib/backup-name"
import { bytes, count, dateTime, duration, parseInstant, relative } from "@/lib/format"
import {
  useAvatarBaseUrl,
  useBackups,
  useConfig,
  useConfigs,
  usePeople,
  useRuns,
  useSaveConfig,
  useSchedule,
} from "@/lib/queries"
import { ScalarControl } from "@/components/steward/config-controls"
import { PersonIdentity } from "@/components/steward/identity"
import { PageHeader } from "@/components/steward/page-header"
import { Panel } from "@/components/steward/panel"
import { Stat } from "@/components/steward/stat"
import { RunStatus } from "@/components/steward/status"
import { Empty, Failure, Loading } from "@/components/steward/query-state"
import { Badge } from "@/components/ui/badge"
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

/**
 * Everything about the backups, in one place (steward/95).
 *
 * <h2>Why it is a page and not four halves of other pages</h2>
 * Till, 2026-09-17: the backups were spread over Operations (the archive list and the runs),
 * Settings (the age threshold the light turns on) and the start page (one tile), and the one thing
 * none of them carried was where an offsite copy would go. A copy that only exists on the disk it
 * is a copy of is the one fact an operator has to be able to read in a second, so the target is on
 * this page and is typed here rather than in `setup.sh`.
 *
 * <h2>Till's second round, 2026-09-18</h2>
 * The list page shipped first and Till reviewed it against a running instance rather than the
 * wireframes: the volumes panel and the archive listing both said less than the runs table already
 * did, "Requested by" printed a raw string nobody could read as a person, and the remote target
 * and the retention numbers took up permanent space for something read once in a while. This is
 * that second round - see the ticket's own review section for the nine items, one by one:
 *
 * <ol>
 *   <li>"On disk" is "Storage available" now, and says plainly that nothing queries the Storage
 *       Box yet rather than inventing a number for it - see {@link Summary}.</li>
 *   <li>The whole run row is a link, not only the number - see {@link Runs}.</li>
 *   <li>"Volumes" is "Archives", and counts files rather than drawing a fraction nobody without
 *       the run's own report line could interpret - see {@link Runs}.</li>
 *   <li>"Requested by" is "Initiated by" and goes through {@link PersonIdentity}, the one place a
 *       Discord id is allowed to be resolved to a person - see {@link Runs} and
 *       {@link StewardUi.ActorFields} on the backend, which reads `requested_by` apart the same
 *       way the unified actions feed already does.</li>
 *   <li>The volumes panel is gone - it repeated the newest run's own report line for line.</li>
 *   <li>The archive listing is gone from this page; a run's own archives are listed, and made
 *       downloadable, on its own detail page - see {@link BackupRunDetailPage} and the
 *       `/api/backups/{name}/download` route this ticket also built.</li>
 *   <li>The remote target and the schedule are both dialogs off the top-right corner now, not
 *       permanent panels - see {@link DestinationDialog} and {@link ScheduleDialog}.</li>
 *   <li>Every route this page reads is `Gate.KEY_HELD`; only the two saves are `Gate.KEY_FRESH`,
 *       exactly as they already were. Nothing changed here - it is written down because it is the
 *       thing the ninth item leans on.</li>
 *   <li>The retention numbers moved into the schedule dialog, with a sentence underneath that
 *       computes what they mean against {@code Retention}'s own algorithm - see
 *       {@link retentionSentence}.</li>
 * </ol>
 *
 * <h2>The two rules this page is held to</h2>
 * A secret is never drawn: the destination dialog sends the two keys the way every other
 * credential in this interface is sent, as "set" or "not set", and typing a new one does not
 * require having seen the old one. And nothing here starts or restores anything - asking for a run
 * lives on Operations, where the countdown and the confirmation already stand, and a restore is
 * the host's own script on purpose (a way back that runs inside the stack is no way back at all).
 */
export function BackupsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Backups"
        actions={
          <div className="flex items-center gap-2">
            <DestinationDialog />
            <ScheduleDialog />
          </div>
        }
      />

      <Summary />

      <Runs />
    </div>
  )
}

// --- the file this page reads and writes ---------------------------------------------------------

/**
 * `steward-worker/steward.yml`, looked up rather than spelled out.
 *
 * The id is the path out of `/api/config`, and that listing is what the backend actually found
 * under the mount - a constant here would be a path this page believes in and the worker may not
 * have. A deployment whose worker config is missing or unreadable gets no form rather than a form
 * that cannot save.
 */
function useWorkerConfig() {
  const configs = useConfigs()
  const file = configs.data?.find(
    (location) =>
      location.service === "steward-worker" && location.name.split("/").pop() === "steward.yml",
  )
  const document = useConfig(file?.path ?? "", Boolean(file))
  const parsed = document.data && !document.data.raw ? document.data : undefined
  return { file: file?.path, document: parsed, pending: configs.isPending || document.isPending }
}

/** One key of that file, by its dotted path - `undefined` when the file has no such key. */
function entryAt(document: ParsedConfigDocument | undefined, path: string): ConfigEntry | undefined {
  return document?.entries.find((entry) => entry.path === path)
}

/**
 * The draft, the computed changes and the save state for a fixed list of keys of one document -
 * shared between {@link DestinationDialog} and {@link ScheduleDialog} rather than written twice.
 *
 * `keys` has to be a stable reference (a module-level constant, as both callers below pass): a new
 * array literal every render would invalidate the memo on every render and defeat the point of it.
 */
function useConfigDraft(document: ParsedConfigDocument | undefined, keys: readonly string[]) {
  const [draft, setDraft] = useState<Record<string, string>>({})

  // The answer to a save IS the file as it now reads, so a write empties the form's own state and
  // what is on screen afterwards is what was written rather than what this browser hoped for.
  useEffect(() => setDraft({}), [document])

  const entries = useMemo(
    () => keys.map((path) => entryAt(document, path)).filter(
      (entry): entry is ConfigEntry => entry !== undefined,
    ),
    [document, keys],
  )

  const changes: ConfigChanges = {}
  for (const entry of entries) {
    const typed = draft[entry.path]
    if (typed === undefined) continue
    // A secret has no value to compare against - it was never sent - so anything typed into one is
    // a change. Everything else is compared with what the file says.
    if (entry.secret ? typed !== "" : typed !== (entry.value ?? "")) changes[entry.path] = typed
  }
  const changed = Object.keys(changes).length

  return { entries, draft, setDraft, changes, changed }
}

/** The value of one of this draft's entries as it would be saved right now - typed, or the file's. */
function draftValue(entries: ConfigEntry[], draft: Record<string, string>, path: string): string {
  const typed = draft[path]
  if (typed !== undefined) return typed
  return entries.find((entry) => entry.path === path)?.value ?? ""
}

// --- the numbers ----------------------------------------------------------------------------------

/**
 * Three numbers, roughly two to a row on a phone.
 *
 * Mobile first, the standing rule since steward/64. "Storage available" replaced "On disk"
 * (Till's review, item 1): the old tile summed the local directory, which is exactly the copy an
 * offsite target exists to not be the only one of. Nothing in this stack queries the Storage Box
 * yet - `backup.remote`'s own doc says as much, "nothing in this service uploads yet" - so this
 * tile says that honestly rather than drawing a number for either the free space or what is stored
 * there. The day steward-worker can ask S3 a `HEAD` on its bucket, this is the one place that
 * answer needs to land.
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
        value={newest ? relative(newest.modified) : backups.data ? "none" : "–"}
        tone={backups.data && !newest ? "down" : undefined}
        hint={newest ? newest.human : backups.data ? "no finished backup" : "–"}
      />
      {/*
        Neutral, not `tone="warn"` (orchestrator's call, 2026-09-19): a tile that will read exactly
        this until somebody builds the S3 query would be a permanently amber tile, and an amber
        tile in this interface means something needs attention tonight. "Not tracked" is a fact
        about the feature, not an alarm about the backups.
      */}
      <Stat
        label="Storage available"
        value="not tracked"
        hint="steward-worker does not query the Storage Box yet"
      />
      <Stat
        label="Next"
        value={schedule.data?.nextBackupAt ? relative(schedule.data.nextBackupAt) : "–"}
        hint={
          schedule.data?.nextBackupAt
            ? `${at ?? schedule.data.backupAt} ${schedule.data.zone}`
            : schedule.isPending
              ? "–"
              : "no nightly clock"
        }
      />
    </div>
  )
}

// --- the runs -------------------------------------------------------------------------------------

/** The `BACKUP` rows of `update_request`, newest first. */
function backupRuns(runs: Run[] | undefined): Run[] {
  return (runs ?? []).filter((run) => run.kind === "BACKUP")
}

/** How many of a run's report lines were actually saved, and how many there were. */
function saved(run: Run): { saved: number; total: number } {
  const lines = run.report?.services ?? []
  return { saved: lines.filter((line) => line.state === "SAVED").length, total: lines.length }
}

function ran(run: Run): string {
  const started = parseInstant(run.started)
  const finished = parseInstant(run.finished)
  if (!started || !finished) return "–"
  return duration((finished.getTime() - started.getTime()) / 1000)
}

/**
 * The last backup runs, whole rows clickable through to their own detail page.
 *
 * Till's review, items 2 to 4: every cell of a row is a link now, not only the run number
 * (`onClick` on the row plus a `Link` kept on the number itself for keyboard and middle-click);
 * "Volumes" is "Archives" and counts the files a run actually wrote rather than drawing a fraction
 * that needed the run's own report line to make sense of; and "Requested by" is "Initiated by",
 * drawn through {@link PersonIdentity} so a raw Discord id is never the thing on screen - see
 * `StewardUi.ActorFields` for where `requestedBy` is read apart into the id, the plain label or
 * the flag that says this was Steward's own nightly clock.
 */
function Runs() {
  const navigate = useNavigate()
  const runs = useRuns(40)
  const people = usePeople()
  const avatarBaseUrl = useAvatarBaseUrl()
  const now = Date.now()
  const rows = backupRuns(runs.data).slice(0, 8)

  return (
    <Panel title="Runs">
      {runs.isPending ? (
        <Loading rows={4} />
      ) : runs.error ? (
        <Failure error={runs.error} onRetry={runs.refetch} />
      ) : rows.length === 0 ? (
        <Empty title="No backup run yet" />
      ) : (
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
            {rows.map((run) => {
              const archives = saved(run)
              const known = run.actorDiscordId
                ? people.data?.find((person) => person.discordId === run.actorDiscordId)
                : undefined
              return (
                <TableRow
                  key={run.id}
                  className="cursor-pointer"
                  onClick={() =>
                    navigate({ to: "/operations/backups/$id", params: { id: String(run.id) } })
                  }
                >
                  <TableCell data-label="Run" className="font-medium tnum">
                    <Link
                      to="/operations/backups/$id"
                      params={{ id: String(run.id) }}
                      className="underline-offset-4 hover:text-primary hover:underline"
                      onClick={(event) => event.stopPropagation()}
                    >
                      #{run.id}
                    </Link>
                  </TableCell>
                  <TableCell data-label="When">{dateTime(run.started || run.requested)}</TableCell>
                  <TableCell data-label="Status">
                    <RunStatus status={run.status} />
                  </TableCell>
                  <TableCell data-label="Archives" className="text-right tnum">
                    {archives.total === 0 ? "–" : count(archives.saved)}
                  </TableCell>
                  <TableCell data-label="Took" className="text-right tnum">
                    {ran(run)}
                  </TableCell>
                  <TableCell data-label="Initiated by" className="text-muted-foreground">
                    {run.system ? (
                      <PersonIdentity system now={now} />
                    ) : run.actorDiscordId ? (
                      <PersonIdentity
                        discordId={run.actorDiscordId}
                        discordUsername={known?.discordUsername}
                        discordUsernameUpdated={known?.discordUsernameUpdated}
                        discordDisplayName={known?.discordDisplayName}
                        discordDisplayNameUpdated={known?.discordDisplayNameUpdated}
                        discordAvatarUrl={known?.discordAvatarUrl}
                        discordAvatarUrlUpdated={known?.discordAvatarUrlUpdated}
                        mcUuid={known?.minecraftUuid}
                        mcName={known?.mcName}
                        mcNameUpdated={known?.mcNameUpdated}
                        avatarBaseUrl={avatarBaseUrl.data}
                        now={now}
                      />
                    ) : (
                      <span className="truncate">{run.actorLabel || run.source}</span>
                    )}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
    </Panel>
  )
}

// --- the offsite target, in its own dialog now (Till's review, item 7) ----------------------------

/** The five keys of `backup.remote`, in the order the form draws them. */
const REMOTE_KEYS = [
  "backup.remote.endpoint",
  "backup.remote.bucket",
  "backup.remote.prefix",
  "backup.remote.access-key",
  "backup.remote.secret-key",
] as const

/**
 * Where a copy goes that is not on this disk (steward/95, and steward/08 hangs off it) - a dialog
 * off the page header rather than a permanent panel, since it is read far less often than the runs
 * below it.
 *
 * <h2>It writes into `steward.yml`, and it is deliberately not a form of its own</h2>
 * The values are five keys of the worker's own config file, saved through the same `PUT
 * /api/config/…` every other setting in this interface goes through - revision and all, so two open
 * browsers get a 409 rather than a silent overwrite. Inventing an endpoint for these five would
 * have meant a second way to write a config file, and the second one is the one that forgets the
 * revision.
 *
 * <h2>The keys are never read back, which is what makes typing them here safe</h2>
 * `access-key` and `secret-key` are `@Secret` in `StewardSpec`, so the worker sends them as
 * `filled` and no value. {@link ScalarControl} draws exactly that: a password field that says
 * whether something is set. Nothing on this page can show a credential, because nothing on this
 * page ever receives one.
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
              {changed > 0 ? (
                <span className="text-sm text-muted-foreground tnum">{changed} changed</span>
              ) : null}
            </div>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

// --- the nightly clock and the retention it keeps, in its own dialog (Till's review, items 7 & 9) --

/** `backup.at` and the four keys of `backup.retention`, in the order the dialog draws them. */
const SCHEDULE_KEYS = [
  "backup.at",
  "backup.retention.daily",
  "backup.retention.weekly",
  "backup.retention.monthly",
  "backup.retention.collapse-after-days",
] as const

/**
 * The seven, in the order a week is read, with what the config file calls each one.
 *
 * The file holds `java.time.DayOfWeek` names because that is what the worker parses them into;
 * `NightlyClock` also accepts the three-letter form and any casing, so a file edited by hand is
 * still read here - `chosenDays` compares on the first three letters for exactly that reason.
 */
const WEEKDAYS = [
  { label: "Mon", value: "MONDAY" },
  { label: "Tue", value: "TUESDAY" },
  { label: "Wed", value: "WEDNESDAY" },
  { label: "Thu", value: "THURSDAY" },
  { label: "Fri", value: "FRIDAY" },
  { label: "Sat", value: "SATURDAY" },
  { label: "Sun", value: "SUNDAY" },
] as const

/** The `backup.days` key, which is a LIST and therefore not part of the scalar draft. */
const DAYS_KEY = "backup.days"

/**
 * Which of the seven a list of config entries means - by the first three letters, upper-cased.
 *
 * A file written by hand may say `mon`, `Mon` or `MONDAY`, and the worker reads all three
 * (`NightlyClock.weekdays`). Anything that matches no day is dropped here exactly as it is
 * dropped there, so what the badges show is what the schedule does.
 */
function chosenDays(items: string[] | undefined): string[] {
  if (items === undefined) return []
  const stems = new Set(items.map((item) => item.trim().slice(0, 3).toUpperCase()))
  return WEEKDAYS.filter((day) => stems.has(day.value.slice(0, 3))).map((day) => day.value)
}

/** A whole number out of a draft, falling back to `otherwise` when it does not parse as one. */
function intOr(value: string, otherwise: number): number {
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : otherwise
}

/**
 * What `daily`, `weekly`, `monthly` and `collapseAfterDays` mean, in one sentence - matched against
 * `Retention.expired`'s own algorithm rather than a paraphrase of it, so this text can never drift
 * from what a sweep actually does:
 *
 * <ul>
 *   <li>the schedule is staggered and counted from now, not stacked on top of the daily window
 *       (`Retention`'s own javadoc: "eight weekly copies means eight weeks of history, not eight
 *       weeks on top of the daily ones") - so the total kept is AT MOST daily + weekly + monthly;</li>
 *   <li>several runs on one day collapse to the last of them, but only once they are older than
 *       the grace period - a backup taken by hand minutes ago is never the one a sweep removes.</li>
 * </ul>
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
 * The nightly clock and how long it keeps what it writes - a dialog rather than a permanent panel,
 * for the same reason the destination is one.
 *
 * <h2>The weekdays are wired, and they were not when this dialog was first drawn</h2>
 * Till asked for "weekdays clickable" beside the time. Nothing in the stack had the concept, so
 * the first round drew seven always-on badges with a caption admitting they did nothing. They
 * write `backup.days` now: a LIST key on the worker, read by `NightlyClock`, which skips a night
 * that is not one of them instead of firing anyway. A day nobody picked is a night with no
 * backup - the caption under the badges says which, rather than leaving it to be discovered by a
 * missing archive.
 *
 * <p>`backup.days` is a LIST and every other key here is a scalar, which is why it is kept beside
 * {@link useConfigDraft}'s draft rather than inside it: that draft is `Record<string, string>` on
 * purpose, and a list flattened into a string is how `stop-services: smp` gets written over a
 * sequence (see `ConfigChange`'s own comment on the worker).</p>
 */
function ScheduleDialog() {
  const { file, document, pending } = useWorkerConfig()
  const save = useSaveConfig(file ?? "")
  const { entries, draft, setDraft, changes, changed } = useConfigDraft(document, SCHEDULE_KEYS)

  // The list key, kept out of the scalar draft - see the class comment. `undefined` is "nothing
  // touched yet", so the file's own list is what is drawn until somebody clicks a badge.
  const [pickedDays, setPickedDays] = useState<string[] | undefined>(undefined)
  useEffect(() => setPickedDays(undefined), [document])

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
              <div className="flex flex-wrap gap-1.5">
                {WEEKDAYS.map((day) => {
                  const on = days.includes(day.value)
                  return (
                    <Badge
                      key={day.value}
                      asChild
                      variant={on ? "default" : "outline"}
                      className={daysEntry && document.writable ? "cursor-pointer" : undefined}
                    >
                      <button
                        type="button"
                        aria-pressed={on}
                        disabled={!daysEntry || !document.writable || save.isPending}
                        onClick={() =>
                          setPickedDays(
                            on
                              ? days.filter((chosen) => chosen !== day.value)
                              : WEEKDAYS.filter(
                                  (candidate) =>
                                    candidate.value === day.value || days.includes(candidate.value),
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
              {!daysEntry ? (
                <p className="text-xs text-muted-foreground">
                  This worker's config has no backup.days yet. A worker that has started since the
                  key was added writes it in.
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
              {allChanged > 0 ? (
                <span className="text-sm text-muted-foreground tnum">{allChanged} changed</span>
              ) : null}
            </div>
          </div>
        )}
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}

// --- one backup run's own archives (Till's review, item 6) -----------------------------------------

/**
 * Which of the files under `backup.output-root` belong to one run.
 *
 * <h2>Why a time window, and not a name</h2>
 * A run's report names volumes, not files - `saved(run)` already reads that - and an archive's own
 * name carries a stamp taken when that one file was written, not the run's start. Several archives
 * from one run are therefore several different stamps, each a few seconds apart. The window is
 * `[started, finished]` widened by a minute on each side: generous enough to catch every archive a
 * run of ordinary length wrote, and narrow enough that two nights never overlap (they are, at
 * minimum, `backup.at` apart - hours, not minutes). A run this page cannot date at all matches
 * nothing, rather than guessing.
 *
 * <h2>Why this is not `operations.tsx`'s own matching</h2>
 * That page's `matchingRun` answers the opposite question (given a file, which run wrote it) and is
 * private to a file this ticket was explicitly told not to touch while another agent was using it.
 * Reusing it was not an option; this is a independent implementation of the same idea; a follow-up
 * ticket to unify the two once both are done would be sound.
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
    .sort((left, right) => left.name.localeCompare(right.name))
}

/**
 * What one file is, out of its name - and `.partial` said plainly.
 *
 * A `.partial` is either a run in flight or one that was abandoned, and it cannot be restored
 * either way. The word is in the column rather than in a legend somewhere, because the column is
 * where somebody is looking when it matters.
 */
function holds(backup: Backup): string {
  const what = archived(backup.name)
  const subject = what.kind === "database" ? "database" : (what.subject ?? "unknown")
  return backup.partial ? `${subject}, partial` : subject
}

/**
 * One backup run: what it was and, now that the list page no longer carries them, its own
 * archives - each one downloadable through the route this ticket built (steward-worker resolves
 * and streams the file; steward-ui proxies it the same way it already proxies a log follow, with
 * `worker.stream`).
 *
 * This is what `/operations/backups/$id` now draws. It used to be one archive file's own page,
 * keyed by filename (`operations.tsx`'s `OperationsBackupPage`, orphaned by this change rather than
 * deleted - see the ticket for why). The route is a better fit for a run: a run wrote several
 * archives, not one, and "a backup" in every other sentence on this page already means the run, not
 * one file out of it.
 */
export function BackupRunDetailPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  const runs = useRuns(80)
  const backups = useBackups()
  const run = runs.data?.find((candidate) => String(candidate.id) === id)
  const files = run ? archivesOf(run, backups.data ?? []) : []

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={`Backup #${id}`} />

      {runs.isPending || backups.isPending ? (
        <Loading rows={4} />
      ) : runs.error ? (
        <Failure error={runs.error} onRetry={runs.refetch} />
      ) : !run ? (
        <Empty title="No such run" note="It may be older than this page's own window." />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-x-4 gap-y-5 lg:grid-cols-4">
            <Stat label="Status" value={<RunStatus status={run.status} />} />
            <Stat label="When" value={dateTime(run.started || run.requested)} />
            <Stat label="Took" value={ran(run)} />
            <Stat label="Archives" value={count(files.length)} />
          </div>

          <Panel title="Archives">
            {files.length === 0 ? (
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
                  {files.map((backup) => (
                    <TableRow key={backup.name}>
                      <TableCell data-label="Archive">
                        <code className="text-xs">{backup.name}</code>
                      </TableCell>
                      <TableCell data-label="Holds">{holds(backup)}</TableCell>
                      <TableCell data-label="Written">{dateTime(backup.modified)}</TableCell>
                      <TableCell data-label="Size" className="text-right tnum">
                        {bytes(backup.bytes)}
                      </TableCell>
                      <TableCell data-label="Download">
                        {backup.partial ? null : (
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
    </div>
  )
}
