import { useMemo, useState } from "react"
import { Link, useParams } from "@tanstack/react-router"
import {
  Archive,
  ArrowRight,
  Check,
  CircleSlash,
  Copy,
  Database,
  Download,
  FileText,
  Play,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  TriangleAlert,
} from "lucide-react"
import { toast } from "sonner"

import type { Backup, ReportChange, ReportLine, Run, ServiceTable } from "@/lib/api"
import {
  LOCALE,
  bytes,
  count,
  dateTime,
  duration,
  parseInstant,
  relative,
  since,
} from "@/lib/format"
import {
  useAskForRun,
  useBackups,
  useRun,
  useRuns,
  useSchedule,
  useServices,
} from "@/lib/queries"
import { PageHeader } from "@/components/steward/page-header"
import { Stat } from "@/components/steward/stat"
import {
  DriftBadge,
  RUN_KIND,
  RunStatus,
  StatusBadge,
  type Tone,
} from "@/components/steward/status"
import { RecreateButton } from "@/components/steward/recreate"
import { Empty, Failure, Loading, QueryState } from "@/components/steward/query-state"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

/**
 * Operations - the five pages about runs, images, backups and the way back (concept §10a).
 *
 * **A run is a row in `update_request`, never a call to a container.** Every button here writes one
 * and stops; the worker claims it once `not_before` has passed. That is what makes a run
 * schedulable and what puts a countdown in front of every player before anything is stopped - and
 * it is why the toasts below speak of a row being written rather than of an update having started.
 */

// --- the vocabulary of a report -----------------------------------------------------------------

/**
 * The stages in the order `UpdateReport.Stage` declares them, without the four endings.
 *
 * Shown in full for every kind rather than filtered per kind. Which stages a BACKUP walks and which
 * an UPDATE walks is the worker's decision, and a trail that guessed it would be quietly wrong on
 * the day the worker changes - so the trail shows all of them and the line underneath says that not
 * every run passes every one.
 */
const TRAIL = [
  "RESOLVING",
  "PLANNED",
  "COUNTDOWN",
  "STOPPING",
  "BACKING_UP",
  "INSTALLING",
  "STARTING",
  "VERIFYING",
] as const

const STAGE_LABEL: Record<string, string> = {
  RESOLVING: "Resolving",
  PLANNED: "Planned",
  COUNTDOWN: "Countdown",
  STOPPING: "Stopping",
  BACKING_UP: "Backing up",
  INSTALLING: "Installing",
  STARTING: "Starting",
  VERIFYING: "Verifying",
  DONE: "Done",
  NOTHING_TO_DO: "Nothing to do",
  FAILED: "Failed",
  CANCELLED: "Cancelled",
}

/** The four stages a run stops at. `NOTHING_TO_DO` is one of them and is not a kind of "done". */
const ENDINGS = new Set(["DONE", "NOTHING_TO_DO", "FAILED", "CANCELLED"])

/**
 * `NOTHING_TO_DO` is grey, not green, and that is the whole point of this function.
 *
 * A run that found nothing to do stopped no server and installed nothing. Painting it in the same
 * colour as a finished update makes the two indistinguishable in a list - and the difference
 * between "it was updated" and "there was nothing to update" is exactly what somebody scanning
 * this table is looking for.
 */
function stageTone(stage: string): Tone {
  if (stage === "DONE") return "ok"
  if (stage === "FAILED") return "down"
  if (stage === "NOTHING_TO_DO" || stage === "CANCELLED") return "idle"
  return "warn"
}

function StageBadge({ stage }: { stage: string }) {
  return <StatusBadge tone={stageTone(stage)}>{STAGE_LABEL[stage] ?? stage}</StatusBadge>
}

const LINE_STATE: Record<string, { label: string; tone: Tone }> = {
  UNCHANGED: { label: "unchanged", tone: "idle" },
  PLANNED: { label: "waiting", tone: "idle" },
  STOPPED: { label: "stopped", tone: "warn" },
  INSTALLED: { label: "installed", tone: "warn" },
  SAVED: { label: "saved", tone: "ok" },
  STARTING: { label: "starting", tone: "warn" },
  HEALTHY: { label: "healthy", tone: "ok" },
  FAILED: { label: "failed", tone: "down" },
}

function LineState({ state }: { state: string }) {
  const known = LINE_STATE[state]
  // An unknown state keeps its enum name rather than disappearing: a worker newer than this jar is
  // a thing to notice, not a thing to hide behind a neutral badge with no text.
  return <StatusBadge tone={known?.tone ?? "idle"}>{known?.label ?? state}</StatusBadge>
}

/**
 * One artefact, and what is happening to it.
 *
 * `UpdateReport.Change.State` has exactly two values, MOVING and UNSUPPORTED, and `to` carries the
 * sentinel `"-"` for the second one. (`src/lib/api.ts` calls the second value UNCHANGED in a
 * comment; the enum in `:common` has no such value. This branches on what the worker actually
 * writes and falls through to the raw words for anything a newer one might add.)
 */
function Change({ change }: { change: ReportChange }) {
  if (change.state === "UNSUPPORTED") {
    return (
      <span className="text-muted-foreground">
        <code className="text-xs">{change.artefact}</code> - no build for this Minecraft
        version
      </span>
    )
  }
  return (
    // `flex-wrap`: MEASURED 2026-09-14 at 390px on /operations/runs/latest. A jar name, the old
    // version, an arrow and the new one is 180px of unbreakable content more than the card has,
    // and a flex row with nowhere to break puts the last two off the right edge of the phone.
    <span className="flex flex-wrap items-center gap-1.5">
      <code className="text-xs">{change.artefact}</code>
      {change.from ? (
        <>
          <span className="text-muted-foreground tnum">{change.from}</span>
          <ArrowRight className="size-3 shrink-0 text-muted-foreground" aria-hidden />
        </>
      ) : (
        <span className="text-muted-foreground">new:</span>
      )}
      <span className="tnum">{change.to}</span>
    </span>
  )
}

/**
 * Whether a change row is a file actually moving.
 *
 * Deliberately not `state === "MOVING"`. `UpdateReports` omits the field entirely when it is MOVING
 * and writes it only for UNSUPPORTED - measured against runs 22 and 24 in this host's database on
 * 2026-09-13 - while steward-ui parses that JSON into the record and re-writes it through Gson,
 * which puts the field back. "Anything that is not UNSUPPORTED" is true of both shapes; a literal
 * comparison is true of one of them, and silently counts nothing on the other.
 */
function isMoving(change: ReportChange): boolean {
  return change.state !== "UNSUPPORTED"
}

/** How long a run took - or, while it is still going, how long it has been going. */
function runSeconds(run: Run): number | null {
  const started = parseInstant(run.started)
  if (started == null) return null
  const finished = parseInstant(run.finished)
  return ((finished ?? new Date()).getTime() - started.getTime()) / 1000
}

/**
 * What a run did, in one line for the table.
 *
 * Counted out of the report rather than taken from a sentence, because there is no sentence: the
 * worker writes structure and every surface renders its own summary (`UpdateReport`'s own comment
 * says so).
 */
function summaryOf(run: Run): string {
  if (run.resultText) return "report unreadable"
  const report = run.report
  if (!report) return run.status === "PENDING" ? "nothing written yet" : "–"
  if (report.stage === "NOTHING_TO_DO") return "nothing to do"

  const parts: string[] = []
  const saved = report.services.filter((line) => line.state === "SAVED")
  if (saved.length > 0) parts.push(`${count(saved.length)} saved`)

  const moving = report.services.filter((line) => line.changes.some(isMoving))
  if (moving.length > 0) {
    const artefacts = moving.reduce(
      (sum, line) => sum + line.changes.filter(isMoving).length,
      0,
    )
    parts.push(
      `${count(moving.length)} ${moving.length === 1 ? "service" : "services"}, ` +
        `${count(artefacts)} ${artefacts === 1 ? "artefact" : "artefacts"}`,
    )
  }

  const failed = report.services.filter((line) => line.state === "FAILED")
  if (failed.length > 0) parts.push(`${count(failed.length)} failed`)

  if (parts.length > 0) return parts.join(" · ")
  return report.services.length === 0 ? "no line in the report" : "no change"
}

const SOURCE_LABEL: Record<string, string> = {
  DISCORD: "Discord",
  GAME: "in game",
  CONSOLE: "Interface/console",
}

// --- asking for a run ----------------------------------------------------------------------------

type Kind = "UPDATE" | "BACKUP" | "RESTART"

/**
 * How long before the worker's own backup "tonight" lands.
 *
 * Before that clock rather than on top of it: update and backup take the same lock, so two runs at
 * the same minute are one run waiting for the other with the network already down. Forty-five
 * minutes is the gap the default configuration has (04:00 against `backup.at` 04:45) and is far
 * more than a run of either kind takes.
 */
export const MINUTES_BEFORE_BACKUP = 45

/** The hour "tonight" means when there is no nightly backup to stay out of the way of. */
const NIGHT_HOUR = 4

/**
 * When "tonight" is.
 *
 * THIS USED TO BE 04:00 IN THE BROWSER'S TIME ZONE, and the dialog said it was "shortly before the
 * worker's own backup clock" - a promise it could not keep. The worker's clock runs in the
 * container's zone (compose sets `TZ`), so an admin an hour east of the host scheduled 03:00 there,
 * and one two hours west scheduled 06:00: after the backup, which is exactly the collision the
 * offer exists to avoid. So the moment is derived from what the worker says its next backup is.
 *
 * Exported and pure because this frontend has no test runner: this is the part with arithmetic in
 * it, and it can at least be read as one function rather than found inside a component.
 */
export function tonight(nextBackupAt: string | null | undefined, now = new Date()): Date {
  const backup = nextBackupAt ? new Date(nextBackupAt) : null
  if (!backup || Number.isNaN(backup.getTime())) {
    // No nightly backup at all, so there is nothing to stay out of the way of and no zone to
    // borrow. Four o'clock here, and the dialog says that is what it is.
    const target = new Date(now)
    target.setHours(NIGHT_HOUR, 0, 0, 0)
    if (target.getTime() <= now.getTime()) target.setDate(target.getDate() + 1)
    return target
  }
  let target = backup.getTime() - MINUTES_BEFORE_BACKUP * 60_000
  // Less than three quarters of an hour to the backup: tonight's slot has gone, take tomorrow's
  // rather than asking for a moment in the past, which the worker would run immediately.
  //
  // A WHILE AND NOT AN IF. One day forward only rescues a schedule less than about 23 hours stale,
  // and this one can be older than that: `useSchedule` has an hour of staleTime, no refetch
  // interval, and main.tsx turns refetchOnWindowFocus off for every query - so a dashboard left
  // open over a weekend still holds Friday's `nextBackupAt`. What came back then was a moment in
  // the past, which lands in `not_before` on the update_request row, which the worker takes as
  // "now": smp and the network stop while somebody is standing in the world. That is precisely
  // the collision this function exists to avoid.
  const day = 24 * 60 * 60 * 1000
  while (target <= now.getTime()) target += day
  return new Date(target)
}

const ASKS: Record<
  Kind,
  { title: string; what: string; warning?: string; icon: typeof RefreshCw }
> = {
  UPDATE: {
    title: "Update eintragen",
    what:
      "Asks every source for the newest version, stops the services where something changes, swaps their jars and starts them again. If nothing is new, nothing is stopped - the run then ends at \"Nothing to do\".",
    icon: RefreshCw,
  },
  BACKUP: {
    title: "Enter a backup",
    what:
      "Takes the database dump first (nothing is stopped for that), then stops smp, network-control and the bot, packs every volume and starts everything again.",
    warning: "While the packing runs, the network cannot be reached.",
    icon: Archive,
  },
  RESTART: {
    title: "Enter a restart",
    what: "Stops the services of the network and starts them again. Nothing is swapped.",
    warning: "A restart throws every player off the SMP.",
    icon: RotateCcw,
  },
}

/**
 * One button, one confirmation, two timings.
 *
 * The dialog is not a formality: all three of these stop servers, and the middle one is the only
 * page in this interface that can empty the SMP. So it names what will happen before it happens,
 * and it offers "tonight" beside "now" - which costs one number in the request body and is
 * the difference between an operator waiting up and an operator going to bed.
 */
function AskButton({ kind, variant = "outline" }: { kind: Kind; variant?: "default" | "outline" }) {
  const ask = useAskForRun()
  const schedule = useSchedule()
  const spec = ASKS[kind]
  const Icon = spec.icon
  const night = tonight(schedule.data?.nextBackupAt)

  /**
   * The delay, read at the click and not at the render.
   *
   * `night` above is a label and may be minutes or hours old by the time anybody presses anything -
   * a dialog opened at 03:50 for a 04:00 slot held about 600 seconds, and pressing it at 04:05 sent
   * those same 600 seconds, which put the run at 04:15: after the backup it was supposed to stay
   * out of the way of. The number that leaves this page is computed from the clock at the moment
   * the operator commits to it.
   */
  const delayNow = () =>
    Math.max(1, Math.round((tonight(schedule.data?.nextBackupAt).getTime() - Date.now()) / 1000))

  const submit = (delaySeconds?: number) => {
    ask.mutate(
      { kind, delaySeconds },
      {
        onSuccess: (run) => {
          toast.success(`${RUN_KIND[kind]} entered as run #${run.id}`, {
            description: delaySeconds
              ? `steward-worker picks the row up no earlier than ${dateTime(run.notBefore)}.`
              : "steward-worker picks the row up on its next pass.",
          })
        },
        onError: (error) => {
          toast.error(`${RUN_KIND[kind]} was not entered`, { description: String(error) })
        },
      },
    )
  }

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant={variant} disabled={ask.isPending}>
          <Icon aria-hidden />
          {RUN_KIND[kind]}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{spec.title}</AlertDialogTitle>
          <AlertDialogDescription>{spec.what}</AlertDialogDescription>
        </AlertDialogHeader>

        {/*
          WHAT THIS DIALOG USED TO ALSO SAY, and what is still true (2026-09-14): the button only
          writes a row into `update_request`; steward-worker picks it up once its moment has come
          and runs a countdown every player sees before each stop. And there is no cancel button
          here, because the API so far knows only entering a run and reading one. Both are the
          mechanism explaining itself to somebody who has already decided, so neither is on screen.
        */}
        <div className="flex flex-col gap-3 text-sm">
          {spec.warning ? (
            <p className="flex items-start gap-2 rounded-md border border-warning/30 bg-warning/8 px-3 py-2 text-warning">
              <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
              {spec.warning}
            </p>
          ) : null}
          <p className="text-muted-foreground">
            "Tonight" means <span className="text-foreground tnum">{dateTime(night)}</span>
            {schedule.data?.nextBackupAt ? (
              <>
                {" "}
                - {MINUTES_BEFORE_BACKUP} minutes before the worker's own backup clock (
                {schedule.data.backupAt} {schedule.data.zone}), so the two do not fight over the
                same lock.
              </>
            ) : schedule.isPending ? (
              <> - the worker's backup clock is being read right now.</>
            ) : (
              <>
                {" "}
                - {NIGHT_HOUR}:00 in this browser's time zone. The worker has no nightly backup
                entered (<code className="text-xs">backup.at</code> is empty), so there is no second
                clock to avoid.
              </>
            )}
          </p>
        </div>

        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            variant="outline"
            disabled={schedule.isPending}
            onClick={() => submit(delayNow())}
          >
            Tonight
          </AlertDialogAction>
          <AlertDialogAction
            variant={kind === "RESTART" ? "destructive" : "default"}
            onClick={() => submit()}
          >
            Now
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

function AskBar() {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <AskButton kind="UPDATE" variant="default" />
      <AskButton kind="BACKUP" />
      <AskButton kind="RESTART" />
    </div>
  )
}

// --- the drift table, shared by /operations and /operations/plan ---------------------------------------

/** Whatever wants attention first: OUTDATED, then UNKNOWN, then UP_TO_DATE, then by name. */
const DRIFT_RANK: Record<string, number> = { OUTDATED: 0, UP_TO_DATE: 2 }

function DriftCard() {
  const services = useServices()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Images</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryState
          query={services}
          rows={6}
          empty={{
            title: "No container in the project",
            note: "steward-worker answered, but no container carries the compose project label.",
          }}
          isEmpty={(table: ServiceTable) => table.services.length === 0}
        >
          {(table) => {
            const rows = [...table.services].sort(
              (left, right) =>
                (DRIFT_RANK[left.drift] ?? 1) - (DRIFT_RANK[right.drift] ?? 1) ||
                left.service.localeCompare(right.service, LOCALE),
            )
            return (
              <>
                <p className="text-xs text-muted-foreground">
                  {table.drift.checkedAt
                    ? `Registry last asked ${relative(table.drift.checkedAt)} (${dateTime(table.drift.checkedAt)}) - that is the age of this comparison, not of the row beside it.`
                    : "The registry has not been asked yet; no row below is a comparison."}
                </p>
                {table.drift.reached === false ? (
                  <p className="flex items-start gap-2 text-xs text-warning">
                    <TriangleAlert className="mt-0.5 size-3.5 shrink-0" aria-hidden />
                    The registry could not be reached
                    {table.drift.reason ? ` (${table.drift.reason})` : ""}.
                    {table.drift.message ? ` ${table.drift.message}` : ""}
                  </p>
                ) : null}

                <Table className="steward-table">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[14rem]">Service</TableHead>
                      <TableHead>Image</TableHead>
                      <TableHead className="w-[8rem]">Compared</TableHead>
                      <TableHead className="w-[10rem] text-right">Container</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((service) => (
                      <TableRow key={service.service}>
                        <TableCell data-label="Service" className="font-medium">
                          <Link
                            to="/services/$name"
                            params={{ name: service.service }}
                            className="underline-offset-4 hover:text-primary hover:underline"
                          >
                            {service.service}
                          </Link>
                        </TableCell>
                        <TableCell data-label="Image" className="text-muted-foreground">
                          <code className="text-xs">{service.image}</code>
                        </TableCell>
                        <TableCell data-label="Compared">
                          <DriftBadge drift={service.drift} />
                        </TableCell>
                        <TableCell data-label="Container" className="text-right">
                          <RecreateButton service={service.service} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {table.drift.unverifiable.length > 0 ? (
                  <p className="text-xs text-muted-foreground">
                    Unchecked: {table.drift.unverifiable.join(", ")} - either the image carries no
                    registry digest (built here and pushed nowhere), or the registry did not answer
                    for it. The worker tells the two apart internally; here it only says that no
                    comparison was possible. That is not "up to date".
                  </p>
                ) : null}
              </>
            )
          }}
        </QueryState>
      </CardContent>
    </Card>
  )
}

// --- 1. /operations ---------------------------------------------------------------------------------

/**
 * The overview: runs, images and backups on one page.
 *
 * Three questions, in this order: what happened last, is anything behind, and is there a backup I
 * could actually use. The third one comes last on screen and first in consequence - it is what
 * decides whether acting on the other two is safe.
 */
export function OperationsPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Operations"
        actions={<AskBar />}
      />

      <RunsCard />
      <DriftCard />
      <BackupsCard />
    </div>
  )
}

function RunsCard() {
  const runs = useRuns(20)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Runs</CardTitle>
        <CardAction>
          <Button asChild variant="outline" size="sm">
            <Link to="/operations/plan">
              <FileText aria-hidden />
              View the plan
            </Link>
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        <QueryState
          query={runs}
          rows={8}
          empty={{
            title: "No run yet",
            note: "There is no row in update_request - not from this interface, not from Discord, not from the worker's clock.",
          }}
          isEmpty={(rows: Run[]) => rows.length === 0}
        >
          {(rows) => (
            <Table className="steward-table">
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[5rem]">Run</TableHead>
                  <TableHead className="w-[7rem]">Kind</TableHead>
                  <TableHead className="w-[9rem]">Status</TableHead>
                  <TableHead className="w-[14rem]">Requested by</TableHead>
                  <TableHead className="w-[10rem]">When</TableHead>
                  <TableHead className="w-[7rem] text-right">Duration</TableHead>
                  <TableHead>Result</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((run) => (
                  <TableRow key={run.id}>
                    <TableCell data-label="Run" className="font-medium tnum">
                      <Link
                        to="/operations/runs/$id"
                        params={{ id: String(run.id) }}
                        className="underline-offset-4 hover:text-primary hover:underline"
                      >
                        #{run.id}
                      </Link>
                    </TableCell>
                    <TableCell data-label="Kind">{RUN_KIND[run.kind] ?? run.kind}</TableCell>
                    <TableCell data-label="Status">
                      <div className="flex items-center gap-1.5">
                        <RunStatus status={run.status} />
                        {run.report && ENDINGS.has(run.report.stage) === false ? (
                          <StageBadge stage={run.report.stage} />
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell data-label="Requested by" className="truncate text-muted-foreground">
                      {run.requestedBy}
                      <span className="ml-1 text-xs">
                        ({SOURCE_LABEL[run.source] ?? run.source})
                      </span>
                    </TableCell>
                    <TableCell data-label="When"
                      className="text-muted-foreground"
                      title={dateTime(run.requested)}
                    >
                      {relative(run.requested)}
                    </TableCell>
                    <TableCell data-label="Duration" className="text-right tnum text-muted-foreground">
                      {duration(runSeconds(run))}
                    </TableCell>
                    <TableCell data-label="Result" className="truncate">
                      {run.report?.stage === "NOTHING_TO_DO" ? (
                        <span className="flex items-center gap-1.5 text-muted-foreground">
                          <CircleSlash className="size-3.5 shrink-0" aria-hidden />
                          nothing to do
                        </span>
                      ) : (
                        summaryOf(run)
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </QueryState>
      </CardContent>
    </Card>
  )
}

function BackupsCard() {
  const backups = useBackups()

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-sm font-medium">Backups</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <QueryState
          query={backups}
          rows={6}
          empty={{
            title: "No archive on the disk",
            note: "There is no file in the backup directory. A run of kind \"Backup\" creates the first one.",
          }}
          isEmpty={(rows: Backup[]) => rows.length === 0}
        >
          {(rows) => (
            <>
              <Table className="steward-table">
                <TableHeader>
                  <TableRow>
                    <TableHead>File</TableHead>
                    <TableHead className="w-[9rem]">Contents</TableHead>
                    <TableHead className="w-[12rem]">Taken</TableHead>
                    <TableHead className="w-[8rem]">Age</TableHead>
                    <TableHead className="w-[8rem] text-right">Size</TableHead>
                    <TableHead className="w-[9rem]">State</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {rows.map((backup) => (
                    <TableRow key={backup.name}>
                      <TableCell data-label="File" className="font-medium">
                        <Link
                          to="/operations/backups/$id"
                          params={{ id: backup.name }}
                          className="underline-offset-4 hover:text-primary hover:underline"
                        >
                          <code className="text-xs">{backup.name}</code>
                        </Link>
                      </TableCell>
                      <TableCell data-label="Contents" className="text-muted-foreground">
                        <ArchiveKind name={backup.name} />
                      </TableCell>
                      <TableCell data-label="Taken" className="text-muted-foreground">
                        {dateTime(backup.modified)}
                      </TableCell>
                      <TableCell data-label="Age" className="text-muted-foreground">
                        {since(backup.modified)}
                      </TableCell>
                      <TableCell data-label="Size" className="text-right tnum">{bytes(backup.bytes)}</TableCell>
                      <TableCell data-label="State">
                        {backup.partial ? (
                          <StatusBadge
                            tone="warn"
                            title="Either this backup is running right now, or it was aborted. A .partial file cannot be restored."
                          >
                            incomplete
                          </StatusBadge>
                        ) : (
                          <StatusBadge tone="ok">complete</StatusBadge>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <div className="flex flex-wrap items-center gap-4">
                <Stat
                  label="Finished archives"
                  value={count(rows.filter((backup) => !backup.partial).length)}
                  hint={`together ${bytes(
                    rows
                      .filter((backup) => !backup.partial)
                      .reduce((sum, backup) => sum + backup.bytes, 0),
                  )}`}
                />
                <Separator orientation="vertical" className="h-10" />
                {/* One of the three sentences that stay (2026-09-14): it names an absence,
                    and nothing on the screen would otherwise say that there is no second copy. */}
                <p className="max-w-prose text-xs text-muted-foreground">
                  Every archive sits on the same disk as the thing it is a copy of - there is no
                  off-site copy.
                </p>
                <Button asChild variant="outline" size="sm" className="ml-auto">
                  <Link to="/operations/restore">
                    <Download aria-hidden />
                    Restore
                  </Link>
                </Button>
              </div>
            </>
          )}
        </QueryState>
      </CardContent>
    </Card>
  )
}

// --- 2. /operations/plan ----------------------------------------------------------------------------

/**
 * What a run would change - out of the two sources that actually exist for it.
 *
 * **There is no dry run.** The API knows three ways to a run (`POST /api/updates` with UPDATE,
 * BACKUP or RESTART) and none that only calculates. What there is: the report of a run still
 * sitting in `RESOLVING` or `PLANNED` - exactly what a `REPORT` run used to leave behind - and the
 * image comparison. Both are here. An invented preview would be more convenient and would be a lie.
 *
 * A run stands in those two stages for seconds only, so "no resolved run" on this page does not
 * mean there is nothing to do - it means none is in that state at this moment. That sentence used
 * to be a disclosure on the page itself; it is a fact about the mechanism, so it lives here.
 */
export function OperationsPlanPage() {
  const runs = useRuns(20)
  const planned = (runs.data ?? []).find(
    (run) => run.report?.stage === "PLANNED" || run.report?.stage === "RESOLVING",
  )

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Plan"
        actions={<AskButton kind="UPDATE" variant="default" />}
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Last resolved</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {runs.isPending ? (
            <Loading rows={4} />
          ) : runs.error ? (
            <Failure error={runs.error} onRetry={runs.refetch} />
          ) : planned === undefined ? (
            <Empty
              title="No run is in the plan right now"
              note={`Among the last 20 rows there is none whose report still stands at "Resolving" or "Planned". What an update would do can therefore only be read here from the image comparison below.`}
            />
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-4">
                <Stat
                  label="Run"
                  value={
                    <Link
                      to="/operations/runs/$id"
                      params={{ id: String(planned.id) }}
                      className="underline-offset-4 hover:text-primary hover:underline"
                    >
                      #{planned.id}
                    </Link>
                  }
                  hint={`${RUN_KIND[planned.kind] ?? planned.kind} · ${planned.requestedBy}`}
                />
                <Stat
                  label="Stage"
                  value={<StageBadge stage={planned.report!.stage} />}
                  hint={`resolved ${relative(planned.requested)}`}
                />
              </div>
              <ReportLines lines={planned.report!.services} />
              <Notes notes={planned.report!.notes} />
            </>
          )}
        </CardContent>
      </Card>

      <DriftCard />
    </div>
  )
}

// --- 3. /operations/runs/$id ------------------------------------------------------------------------

/**
 * One run, drawn rather than dumped.
 *
 * `useRun` polls every two seconds while the run is unfinished and stops by itself, so the report
 * grows on screen without a socket or an interval of this page's own.
 *
 * `$id` may also be the word `latest`, because the sidebar links there. It is resolved through
 * `useRuns(1)` before any run is asked for - without that, `GET /api/updates/latest` would die on
 * the backend's `Long.parseLong`.
 */
export function OperationsRunPage() {
  const { id } = useParams({ from: "/operations/runs/$id" })
  const wantsNewest = id === "latest"
  const newest = useRuns(1, wantsNewest)
  const resolved = wantsNewest ? (newest.data?.[0]?.id?.toString() ?? "") : id
  const numeric = /^\d+$/.test(resolved)
  const run = useRun(resolved, numeric)

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={numeric ? `Run #${resolved}` : "Run"}
        actions={
          <Button asChild variant="outline">
            <Link to="/operations">
              <ArrowRight aria-hidden />
              All runs
            </Link>
          </Button>
        }
      />

      {wantsNewest && newest.isPending ? (
        <Loading rows={4} />
      ) : wantsNewest && newest.error ? (
        <Failure error={newest.error} onRetry={newest.refetch} />
      ) : wantsNewest && !numeric ? (
        <Empty
          title="No run yet"
          note={`There is no row in update_request for "latest" to point at.`}
        />
      ) : !numeric ? (
        <Empty
          title="Not a run number"
          note={`"${id}" is neither a number nor the word "latest". A run is addressed by the number of its row.`}
        />
      ) : run.isPending ? (
        <Loading rows={4} />
      ) : run.error ? (
        <Failure error={run.error} onRetry={run.refetch} />
      ) : run.data === undefined ? (
        <Empty title="Unknown run" note={`There is no row for #${resolved}.`} />
      ) : (
        <RunDetail run={run.data} />
      )}
    </div>
  )
}

function RunDetail({ run }: { run: Run }) {
  const report = run.report
  const finished = report
    ? ENDINGS.has(report.stage)
    : run.status !== "PENDING" && run.status !== "RUNNING"

  return (
    <>
      <Card>
        <CardContent className="flex flex-wrap items-start gap-6 pt-6">
          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium text-muted-foreground">
              Status
            </span>
            <div className="flex items-center gap-2">
              <RunStatus status={run.status} />
              {report ? <StageBadge stage={report.stage} /> : null}
            </div>
            <span className="text-xs text-muted-foreground">
              {RUN_KIND[run.kind] ?? run.kind} · {SOURCE_LABEL[run.source] ?? run.source}
            </span>
          </div>

          <Separator orientation="vertical" className="h-14" />

          <Stat label="Requested by" value={run.requestedBy} hint={dateTime(run.requested)} />
          <Stat
            label="No earlier than"
            value={dateTime(run.notBefore)}
            hint="the worker does not pick the row up before this"
          />
          <Stat label="Started" value={dateTime(run.started)} hint={relative(run.started)} />
          <Stat
            label="Duration"
            value={duration(runSeconds(run))}
            hint={finished ? dateTime(run.finished) : "still running"}
          />
        </CardContent>
      </Card>

      {report?.stage === "NOTHING_TO_DO" ? (
        <div
          className="flex items-start gap-3 rounded-md border border-border bg-secondary/40 px-4 py-3"
          role="status"
        >
          <CircleSlash className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">Nothing to do.</p>
            <p className="max-w-prose text-sm text-muted-foreground">
              Nothing was stopped, nothing swapped and nothing backed up.
            </p>
          </div>
        </div>
      ) : null}

      {run.savedSomething === false && run.kind === "BACKUP" && finished ? (
        <div
          className="flex items-start gap-3 rounded-md border border-destructive/40 bg-destructive/5 px-4 py-3"
          role="alert"
        >
          <ShieldAlert className="mt-0.5 size-5 shrink-0 text-destructive" aria-hidden />
          <div className="flex flex-col gap-1">
            <p className="text-sm font-medium">This run saved nothing.</p>
            <p className="max-w-prose text-sm text-muted-foreground">
              No line of the report stands at "saved".
            </p>
          </div>
        </div>
      ) : null}

      {report ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm font-medium">Stages</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <StageTrail stage={report.stage} kind={run.kind} />
            {!finished ? (
              <p className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="size-2 animate-pulse rounded-full bg-warning" aria-hidden />
                The report is re-read every two seconds and grows while you watch.
              </p>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Report</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {run.resultText ? (
            <>
              <p className="flex items-start gap-2 text-sm text-warning">
                <TriangleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
                The contents of the <code className="text-xs">result</code> column could not be
                read as a report - an old row, or one from a newer version than this. The raw text
                is therefore shown here.
              </p>
              <pre className="max-h-96 overflow-auto rounded-md border border-border bg-[#0a0a0a] p-3 font-mono text-xs leading-5 whitespace-pre-wrap">
                {run.resultText}
              </pre>
            </>
          ) : !report ? (
            <Empty
              title="No report yet"
              note="The result column is empty. Until the worker has picked the row up, nobody writes into it."
            />
          ) : (
            <>
              <ReportLines lines={report.services} />
              <Notes notes={report.notes} />
            </>
          )}
        </CardContent>
      </Card>
    </>
  )
}

/**
 * Which stations a finished run of each kind really walked.
 *
 * A finished run's report keeps only its last stage, so the trail behind it has to be derived -
 * and it used to be derived as "all", which drew a completed backup as having passed through
 * "Resolving" and "Installing". Neither is something a backup does: `RESOLVING` and `INSTALLING`
 * belong to an update (`Runner#update`), `BACKING_UP` to a backup (`Runner#backup`, the one caller
 * of `UpdateRun#save`), and a restart walks none of the three.
 *
 * A run that ended any other way than DONE gets no ticks at all. `NOTHING_TO_DO` stopped nothing
 * and installed nothing, and a `FAILED` or `CANCELLED` run stopped somewhere this report no longer
 * says - and a grey trail is the honest shape of "not known".
 */
const WALKED: Record<string, ReadonlySet<string>> = {
  UPDATE: new Set(TRAIL),
  BACKUP: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "BACKING_UP", "STARTING", "VERIFYING"]),
  RESTART: new Set(["PLANNED", "COUNTDOWN", "STOPPING", "STARTING", "VERIFYING"]),
}

function StageTrail({ stage, kind }: { stage: string; kind: string }) {
  const reached = TRAIL.indexOf(stage as (typeof TRAIL)[number])
  const ending = ENDINGS.has(stage)
  const walked = stage === "DONE" ? WALKED[kind] : undefined

  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-3">
      {TRAIL.map((step, index) => {
        // A running run has walked everything before its current stage. A finished one has walked
        // what its kind walks - which is not all of them, and not anything at all unless it is DONE.
        const past = ending ? (walked?.has(step) ?? false) : reached >= 0 && index < reached
        const now = index === reached
        return (
          <li key={step} className="flex items-center gap-2">
            <span
              className={
                now
                  ? "flex items-center gap-1.5 rounded-full border border-warning/30 bg-warning/12 px-2 py-0.5 text-xs font-medium text-warning"
                  : past
                    ? "flex items-center gap-1.5 text-xs text-foreground"
                    : "flex items-center gap-1.5 text-xs text-muted-foreground"
              }
            >
              {past && !now ? <Check className="size-3 shrink-0" aria-hidden /> : null}
              {STAGE_LABEL[step]}
            </span>
            {index < TRAIL.length - 1 ? (
              <span className="text-muted-foreground" aria-hidden>
                ·
              </span>
            ) : null}
          </li>
        )
      })}
      {ending ? (
        <li className="flex items-center gap-2">
          <ArrowRight className="size-3 shrink-0 text-muted-foreground" aria-hidden />
          <StageBadge stage={stage} />
        </li>
      ) : null}
    </ol>
  )
}

function ReportLines({ lines }: { lines: ReportLine[] }) {
  if (lines.length === 0) {
    return (
      <Empty
        title="No line in the report"
        note="The report is there but names no service - the run has touched none yet."
      />
    )
  }
  return (
    <Table className="steward-table">
      <TableHeader>
        <TableRow>
          <TableHead className="w-[14rem]">Service</TableHead>
          <TableHead className="w-[10rem]">State</TableHead>
          <TableHead>Changes</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {lines.map((line) => (
          <TableRow key={line.service} className="align-top">
            <TableCell data-label="Service" className="font-medium">{line.service}</TableCell>
            <TableCell data-label="State">
              <LineState state={line.state} />
            </TableCell>
            {/* `whitespace-normal`: the Table component puts `whitespace-nowrap` on every cell,
                which is right for a service name and wrong for a list of them. MEASURED
                2026-09-14 at 1440px: a failed run of network-control drew this table 1922px wide
                on a 1440px screen, because six artefact changes were one unbreakable line. */}
            <TableCell data-label="Changes" className="whitespace-normal">
              {line.changes.length === 0 && !line.detail ? (
                <span className="text-muted-foreground">–</span>
              ) : (
                <div className="flex flex-col gap-1 py-1.5">
                  {line.changes.map((change) => (
                    <Change key={`${line.service}-${change.artefact}`} change={change} />
                  ))}
                  {line.detail ? (
                    <span
                      className={
                        line.state === "FAILED"
                          ? "text-xs text-destructive"
                          : "text-xs text-muted-foreground"
                      }
                    >
                      {line.detail}
                    </span>
                  ) : null}
                </div>
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** What the report has to say that hangs on no service: the pack, the migration, the reason. */
function Notes({ notes }: { notes: string[] }) {
  if (notes.length === 0) return null
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-xs font-medium text-muted-foreground">
        Notes
      </span>
      <ul className="flex flex-col gap-1">
        {notes.map((note) => (
          <li key={note} className="text-sm text-muted-foreground">
            {note}
          </li>
        ))}
      </ul>
    </div>
  )
}

// --- 4. /operations/backups/$id -------------------------------------------------------------------

/**
 * How the worker names what it writes into the backup directory.
 *
 * `TarSnapshots`: `<volume>-<stamp>.tar.zst`, and `<…>.partial` while it is being written.
 * `DatabaseDump`: `nordtal-<stamp>.dump`. Nothing in the run's report carries the file name, so the
 * name is the only thing that says what an archive holds - which is why it is taken apart here
 * rather than printed as one string.
 */
const VOLUME_ARCHIVE = /^(.+)-(\d{8}T\d{6}Z)\.tar\.zst(\.partial)?$/
const DATABASE_DUMP = /^(.+)-(\d{8}T\d{6}Z)\.dump(\.partial)?$/

type Archived = { kind: "volume" | "database" | "unknown"; subject: string | null }

function archived(name: string): Archived {
  const dump = DATABASE_DUMP.exec(name)
  // `DatabaseDump.NAME` is the word the report's line carries for the dump; the file is named after
  // the database, not after that word.
  if (dump) return { kind: "database", subject: "database" }
  const volume = VOLUME_ARCHIVE.exec(name)
  if (volume) return { kind: "volume", subject: volume[1] }
  return { kind: "unknown", subject: null }
}

function ArchiveKind({ name }: { name: string }) {
  const what = archived(name)
  if (what.kind === "database") {
    return (
      <span className="flex items-center gap-1.5">
        <Database className="size-3.5 shrink-0" aria-hidden />
        Database
      </span>
    )
  }
  if (what.kind === "volume") {
    return (
      <span className="flex items-center gap-1.5">
        <Archive className="size-3.5 shrink-0" aria-hidden />
        <span className="truncate">{what.subject}</span>
      </span>
    )
  }
  return <span className="text-muted-foreground">unknown</span>
}

/**
 * One backup.
 *
 * **The run beside it is inferred, not recorded.** No report carries the file name: a backup
 * run's line carries the volume name and a sentence like "saved 512.9 MiB in 4s". So this page
 * looks for a run that was running when the file was written and has a line for exactly that
 * volume - and says on screen that this is what it did. A confident "belongs to run #23" would be
 * wrong on the day two runs sit close together.
 */
export function OperationsBackupPage() {
  const { id } = useParams({ from: "/operations/backups/$id" })
  const backups = useBackups()
  const wantsNewest = id === "latest"

  const backup = useMemo(() => {
    const all = backups.data ?? []
    if (!wantsNewest) return all.find((entry) => entry.name === id)
    // "latest" means the newest FINISHED one: a .partial is not a backup, and sending the sidebar's
    // link to a half-written file would be the one case where the word is actively misleading.
    // NO FALLBACK TO all[0]. It used to be there, and it undid the line above it: a directory
    // holding nothing but a backup that is still being written answered "latest" with that file,
    // labelled "incomplete", on a page whose whole job is to say which backup there is.
    return all.find((entry) => !entry.partial)
  }, [backups.data, id, wantsNewest])

  const runs = useRuns(50)
  const match = useMemo(() => matchingRun(backup, runs.data ?? []), [backup, runs.data])

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={backup ? backup.name : "Backup"}
        actions={
          <Button asChild variant="outline">
            <Link to="/operations/restore">
              <Download aria-hidden />
              Restore
            </Link>
          </Button>
        }
      />

      {backups.isPending ? (
        <Loading rows={4} />
      ) : backups.error ? (
        <Failure error={backups.error} onRetry={backups.refetch} />
      ) : backup === undefined ? (
        <Empty
          title={wantsNewest ? "No backup present" : "Unknown file"}
          note={
            wantsNewest
              ? backups.data && backups.data.length > 0
                ? "There is no finished file in the backup directory yet - what is there is being written or was aborted (.partial)."
                : "There is no file in the backup directory."
              : `"${id}" is not in the backup directory. The cleanup run may have taken it away by now.`
          }
        />
      ) : (
        <>
          <Card>
            <CardContent className="flex flex-wrap items-start gap-6 pt-6">
              <div className="flex flex-col gap-2">
                <span className="text-xs font-medium text-muted-foreground">
                  State
                </span>
                {backup.partial ? (
                  <StatusBadge tone="warn">incomplete</StatusBadge>
                ) : (
                  <StatusBadge tone="ok">complete</StatusBadge>
                )}
                <span className="max-w-xs text-xs text-muted-foreground">
                  {backup.partial
                    ? "The file still carries the .partial suffix. Either it is being written, or the run died doing it - it cannot be restored."
                    : "The file was read back once after writing, and only then renamed."}
                </span>
              </div>

              <Separator orientation="vertical" className="h-14" />

              <Stat
                label="Taken"
                value={dateTime(backup.modified)}
                hint={relative(backup.modified)}
              />
              <Stat label="Age" value={since(backup.modified)} />
              <Stat
                label="Size"
                value={bytes(backup.bytes)}
                hint={`the worker calls it ${backup.human}`}
              />
              <div className="flex min-w-48 flex-col gap-1">
                <span className="text-xs font-medium text-muted-foreground">
                  Contents
                </span>
                <span className="text-sm">
                  <ArchiveKind name={backup.name} />
                </span>
                <span className="text-xs text-muted-foreground">
                  {archived(backup.name).kind === "unknown"
                    ? "The name follows neither of the two patterns the worker writes."
                    : "Read from the file name - nobody looked inside the archive."}
                </span>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm font-medium">The run behind it</CardTitle>
            </CardHeader>
            <CardContent>
              {runs.isPending ? (
                <Loading rows={2} />
              ) : runs.error ? (
                <Failure error={runs.error} onRetry={runs.refetch} />
              ) : match === undefined ? (
                <Empty
                  title="No matching run found"
                  note="Among the last 50 rows there is none that matches in time and name. That does not mean there was none - it means it is no longer among the last 50."
                />
              ) : (
                <div className="flex flex-wrap items-center gap-4">
                  <Stat
                    label="Run"
                    value={
                      <Link
                        to="/operations/runs/$id"
                        params={{ id: String(match.id) }}
                        className="underline-offset-4 hover:text-primary hover:underline"
                      >
                        #{match.id}
                      </Link>
                    }
                    hint={`${RUN_KIND[match.kind] ?? match.kind} · ${match.requestedBy}`}
                  />
                  <Stat label="Status" value={<RunStatus status={match.status} />} />
                  <Stat
                    label="Ran"
                    value={dateTime(match.started)}
                    hint={duration(runSeconds(match))}
                  />
                  <Button asChild variant="outline" size="sm" className="ml-auto">
                    <Link to="/operations/runs/$id" params={{ id: String(match.id) }}>
                      <Play aria-hidden />
                      View the report
                    </Link>
                  </Button>
                </div>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

/** A minute of slack on both ends, because the file's mtime is when the tar was closed. */
const SLACK = 60 * 1000

function matchingRun(backup: Backup | undefined, runs: Run[]): Run | undefined {
  if (!backup) return undefined
  const at = parseInstant(backup.modified)
  const subject = archived(backup.name).subject
  if (at == null || subject == null) return undefined

  return runs.find((run) => {
    const started = parseInstant(run.started)
    if (started == null) return false
    const ended = parseInstant(run.finished) ?? new Date()
    if (at.getTime() < started.getTime() - SLACK) return false
    if (at.getTime() > ended.getTime() + SLACK) return false
    return (run.report?.services ?? []).some((line) => line.service === subject)
  })
}

// --- 5. /operations/restore ----------------------------------------------------------------

/**
 * This page restores nothing - and that is the decision, not a gap in it (concept §10a,
 * 2026-09-12).
 *
 * A restore is needed exactly when the stack is broken. `steward-ui` is part of the stack it would
 * be restoring: a button here works in every situation except the one it would exist for. A path
 * that fails in the emergency is not a path. So the page builds the finished command and a person
 * runs it on the host - which is where they would have to run it in the emergency anyway.
 */
export function OperationsRestorePage() {
  const backups = useBackups()
  const [chosen, setChosen] = useState<string>("")
  // What can actually be restored. A .partial is a file being written or a run that died in the
  // middle of one, and restore.sh will not take it.
  const restorable = useMemo(
    () => (backups.data ?? []).filter((backup) => !backup.partial),
    [backups.data],
  )
  const command = `sudo bash deploy/restore.sh ${chosen || "<archive>"}`

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Restore"
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-sm font-medium">Choose an archive</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {backups.isPending ? (
            <Loading rows={3} />
          ) : backups.error ? (
            <Failure error={backups.error} onRetry={backups.refetch} />
          ) : restorable.length === 0 ? (
            // The empty state is about what can be RESTORED, not about what lies there. A
            // directory holding three .partial files used to draw a selector with three entries,
            // every one of them disabled - a control that cannot be used and does not say why.
            <Empty
              title="No archive on the disk"
              note={
                (backups.data ?? []).length > 0
                  ? "Every file in the backup directory still carries the .partial suffix: it is being written, or the run died doing it. None of them can be restored."
                  : "There is no file in the backup directory that could be restored."
              }
            />
          ) : (
            <div className="flex max-w-xl flex-col gap-1.5">
              <Label htmlFor="restore-archive">Backup</Label>
              <Select value={chosen} onValueChange={setChosen}>
                <SelectTrigger id="restore-archive" className="w-full">
                  <SelectValue placeholder="Choose an archive…" />
                </SelectTrigger>
                <SelectContent>
                  {restorable.map((backup) => (
                    <SelectItem key={backup.name} value={backup.name}>
                      {backup.name} · {bytes(backup.bytes)} · {relative(backup.modified)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}

          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium text-muted-foreground">
              Command
            </span>
            <div className="flex items-center gap-2">
              <code className="min-w-0 flex-1 overflow-x-auto rounded-md border border-border bg-[#0a0a0a] px-3 py-2 font-mono text-xs whitespace-pre">
                {command}
              </code>
              <CopyButton text={command} disabled={!chosen} />
            </div>
            {/* TWO SENTENCES STAY HERE, and they are different in kind (2026-09-14). The
                first is why there is no button, which is the ticket's named exception; the second
                names a real danger, which is the fourth case that exception allows for. */}
            <p className="max-w-prose text-xs text-muted-foreground">
              Steward does not run it: it is a container in the stack it would be restoring.
            </p>
            <p className="max-w-prose text-xs text-warning">
              A volume is overwritten, not added to - everything made since the backup is gone.
            </p>
          </div>
        </CardContent>
      </Card>

    </div>
  )
}

/**
 * Copy to clipboard, with the failure spelled out rather than swallowed.
 *
 * `navigator.clipboard` only exists in a secure context. Behind Caddy that is always true, but a
 * button that silently does nothing on the one day it is not would be worse than no button - so the
 * command stays selectable in the block beside it and a failure says why.
 */
function CopyButton({ text, disabled }: { text: string; disabled?: boolean }) {
  const [copied, setCopied] = useState(false)

  return (
    <Button
      type="button"
      variant="outline"
      disabled={disabled}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text)
          setCopied(true)
          window.setTimeout(() => setCopied(false), 2000)
          toast.success("Command copied")
        } catch {
          toast.error("Cannot copy", {
            description:
              "The clipboard is not available to this page. The command can be selected beside it.",
          })
        }
      }}
    >
      {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
      {copied ? "Copied" : "Copy"}
    </Button>
  )
}
